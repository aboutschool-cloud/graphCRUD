package io.graphcrud.infrastructure;

import io.graphcrud.application.GraphStore;
import io.graphcrud.application.GraphStoreCapabilities;
import io.graphcrud.application.ProjectId;
import io.graphcrud.application.QueryBounds;
import io.graphcrud.application.SnapshotCompletion;
import io.graphcrud.application.SnapshotLease;
import io.graphcrud.application.TableImpactResult;
import io.graphcrud.model.CanonicalFact;
import io.graphcrud.model.NodeId;
import io.graphcrud.model.SnapshotId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class InMemoryGraphStore implements GraphStore {
    private static final GraphStoreCapabilities CAPABILITIES = GraphStoreCapabilities.stageOneReference();

    private final Map<SnapshotId, ProjectId> stagingSnapshots = new HashMap<>();
    private final Map<SnapshotId, ProjectId> snapshotProjects = new HashMap<>();
    private final Map<SnapshotId, ProjectId> partialSnapshots = new HashMap<>();
    private final Map<ProjectId, SnapshotId> activeSnapshots = new HashMap<>();
    private final Map<SnapshotId, List<CanonicalFact>> factsBySnapshot = new HashMap<>();
    private final Set<SnapshotId> sealedSnapshots = new HashSet<>();
    private final Map<SnapshotId, SnapshotCompletion> snapshotCompletions = new HashMap<>();
    private final Map<SnapshotId, Integer> retainedSnapshots = new HashMap<>();
    private final Map<SnapshotId, Long> sealSequences = new HashMap<>();
    private long nextSealSequence;

    @Override
    public GraphStoreCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public synchronized void beginSnapshot(ProjectId projectId, SnapshotId snapshotId) {
        if (factsBySnapshot.containsKey(snapshotId)) {
            throw new IllegalStateException("snapshot already exists: " + snapshotId.value());
        }
        stagingSnapshots.put(snapshotId, projectId);
        snapshotProjects.put(snapshotId, projectId);
        factsBySnapshot.put(snapshotId, new ArrayList<>());
    }

    @Override
    public synchronized void writeFacts(SnapshotId snapshotId, Collection<? extends CanonicalFact> facts) {
        if (!stagingSnapshots.containsKey(snapshotId)) {
            throw new IllegalStateException("snapshot is not staging: " + snapshotId.value());
        }
        var existing = factsBySnapshot.get(snapshotId);
        for (var fact : facts) {
            if (existing.stream().noneMatch(candidate -> candidate.factKind().equals(fact.factKind())
                    && candidate.canonicalId().equals(fact.canonicalId()))) {
                existing.add(fact);
            }
        }
    }

    @Override
    public synchronized void sealSnapshot(SnapshotId snapshotId, SnapshotCompletion completion) {
        var projectId = stagingSnapshots.remove(snapshotId);
        if (projectId == null) {
            throw new IllegalStateException("snapshot is not staging: " + snapshotId.value());
        }
        if (completion == SnapshotCompletion.COMPLETE) {
            activeSnapshots.put(projectId, snapshotId);
        } else {
            partialSnapshots.put(snapshotId, projectId);
        }
        sealedSnapshots.add(snapshotId);
        snapshotCompletions.put(snapshotId, completion);
        sealSequences.put(snapshotId, ++nextSealSequence);
    }

    @Override
    public synchronized void promotePartial(SnapshotId snapshotId) {
        var projectId = partialSnapshots.get(snapshotId);
        if (projectId == null) {
            throw new IllegalStateException("snapshot is not a sealed partial snapshot: " + snapshotId.value());
        }
        activeSnapshots.put(projectId, snapshotId);
    }

    @Override
    public synchronized void discardSnapshot(SnapshotId snapshotId) {
        if (stagingSnapshots.remove(snapshotId) == null) {
            throw new IllegalStateException("snapshot is not staging: " + snapshotId.value());
        }
        factsBySnapshot.remove(snapshotId);
        snapshotProjects.remove(snapshotId);
    }

    @Override
    public synchronized void deleteSnapshot(SnapshotId snapshotId) {
        if (!sealedSnapshots.contains(snapshotId)) {
            throw new IllegalStateException("snapshot is not sealed: " + snapshotId.value());
        }
        if (activeSnapshots.containsValue(snapshotId) || retainedSnapshots.getOrDefault(snapshotId, 0) > 0) {
            throw new IllegalStateException("snapshot is active or retained: " + snapshotId.value());
        }
        sealedSnapshots.remove(snapshotId);
        partialSnapshots.remove(snapshotId);
        snapshotCompletions.remove(snapshotId);
        snapshotProjects.remove(snapshotId);
        sealSequences.remove(snapshotId);
        factsBySnapshot.remove(snapshotId);
    }

    @Override
    public synchronized SnapshotLease retainSnapshot(SnapshotId snapshotId) {
        requireSealed(snapshotId);
        retainedSnapshots.merge(snapshotId, 1, Integer::sum);
        return new SnapshotLease() {
            private boolean closed;

            @Override
            public SnapshotId snapshotId() {
                return snapshotId;
            }

            @Override
            public void close() {
                synchronized (InMemoryGraphStore.this) {
                    if (closed) return;
                    retainedSnapshots.computeIfPresent(snapshotId, (ignored, count) -> count == 1 ? null : count - 1);
                    closed = true;
                }
            }
        };
    }

    @Override
    public synchronized SnapshotLease retainActiveSnapshot(ProjectId projectId) {
        var snapshotId = activeSnapshots.get(projectId);
        if (snapshotId == null) throw new IllegalStateException("project has no active snapshot: " + projectId.value());
        return retainSnapshot(snapshotId);
    }

    @Override
    public synchronized Optional<SnapshotCompletion> sealedSnapshotCompletion(SnapshotId snapshotId) {
        return Optional.ofNullable(snapshotCompletions.get(snapshotId));
    }

    @Override
    public synchronized int cleanupSnapshots(ProjectId projectId, int retainNewest) {
        if (retainNewest < 0) throw new IllegalArgumentException("retainNewest must not be negative");
        var expired = sealedSnapshots.stream()
                .filter(id -> projectId.equals(snapshotProjects.get(id)))
                .sorted(java.util.Comparator.comparingLong((SnapshotId id) -> sealSequences.get(id)).reversed())
                .skip(retainNewest).toList();
        int deleted = 0;
        for (var snapshotId : expired) {
            try { deleteSnapshot(snapshotId); deleted++; } catch (IllegalStateException protectedSnapshot) { /* retained */ }
        }
        return deleted;
    }

    @Override
    public synchronized Optional<SnapshotId> activeSnapshot(ProjectId projectId) {
        return Optional.ofNullable(activeSnapshots.get(projectId));
    }

    @Override
    public synchronized byte[] exportJsonl(SnapshotId snapshotId) {
        requireSealed(snapshotId);
        return CanonicalJsonl.write(factsBySnapshot.get(snapshotId));
    }

    @Override
    public synchronized Optional<CanonicalFact> findFact(SnapshotId snapshotId, String factKind, String canonicalId) {
        requireSealed(snapshotId);
        return factsBySnapshot.get(snapshotId).stream()
                .filter(fact -> fact.factKind().equals(factKind) && fact.canonicalId().equals(canonicalId))
                .findFirst();
    }

    @Override
    public synchronized TableImpactResult tableImpact(SnapshotId snapshotId, NodeId tableId, QueryBounds bounds) {
        requireSealed(snapshotId);
        return TableImpactQuery.execute(
                snapshotId, snapshotCompletions.get(snapshotId), factsBySnapshot.get(snapshotId), tableId, bounds);
    }

    private void requireSealed(SnapshotId snapshotId) {
        if (!sealedSnapshots.contains(snapshotId)) {
            throw new IllegalStateException("snapshot is not sealed: " + snapshotId.value());
        }
    }
}
