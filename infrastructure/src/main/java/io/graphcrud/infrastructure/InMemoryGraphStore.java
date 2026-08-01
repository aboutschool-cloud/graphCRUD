package io.graphcrud.infrastructure;

import io.graphcrud.application.GraphStore;
import io.graphcrud.application.GraphStoreCapabilities;
import io.graphcrud.application.ProjectId;
import io.graphcrud.application.QueryBounds;
import io.graphcrud.application.SnapshotCompletion;
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
    private final Map<SnapshotId, ProjectId> partialSnapshots = new HashMap<>();
    private final Map<ProjectId, SnapshotId> activeSnapshots = new HashMap<>();
    private final Map<SnapshotId, List<CanonicalFact>> factsBySnapshot = new HashMap<>();
    private final Set<SnapshotId> sealedSnapshots = new HashSet<>();
    private final Map<SnapshotId, SnapshotCompletion> snapshotCompletions = new HashMap<>();

    @Override
    public GraphStoreCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public void beginSnapshot(ProjectId projectId, SnapshotId snapshotId) {
        if (stagingSnapshots.putIfAbsent(snapshotId, projectId) != null) {
            throw new IllegalStateException("snapshot already exists: " + snapshotId.value());
        }
        factsBySnapshot.put(snapshotId, new ArrayList<>());
    }

    @Override
    public void writeFacts(SnapshotId snapshotId, Collection<? extends CanonicalFact> facts) {
        if (!stagingSnapshots.containsKey(snapshotId)) {
            throw new IllegalStateException("snapshot is not staging: " + snapshotId.value());
        }
        factsBySnapshot.get(snapshotId).addAll(facts);
    }

    @Override
    public void sealSnapshot(SnapshotId snapshotId, SnapshotCompletion completion) {
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
    }

    @Override
    public void promotePartial(SnapshotId snapshotId) {
        var projectId = partialSnapshots.get(snapshotId);
        if (projectId == null) {
            throw new IllegalStateException("snapshot is not a sealed partial snapshot: " + snapshotId.value());
        }
        activeSnapshots.put(projectId, snapshotId);
    }

    @Override
    public Optional<SnapshotId> activeSnapshot(ProjectId projectId) {
        return Optional.ofNullable(activeSnapshots.get(projectId));
    }

    @Override
    public byte[] exportJsonl(SnapshotId snapshotId) {
        requireSealed(snapshotId);
        return CanonicalJsonl.write(factsBySnapshot.get(snapshotId));
    }

    @Override
    public TableImpactResult tableImpact(SnapshotId snapshotId, NodeId tableId, QueryBounds bounds) {
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
