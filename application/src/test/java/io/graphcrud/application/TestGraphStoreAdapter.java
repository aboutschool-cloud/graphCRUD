package io.graphcrud.application;

import io.graphcrud.model.CanonicalFact;
import io.graphcrud.model.NodeId;
import io.graphcrud.model.SnapshotId;
import java.util.Collection;
import java.util.Optional;

abstract class TestGraphStoreAdapter implements GraphStore {
    @Override public GraphStoreCapabilities capabilities() { return GraphStoreCapabilities.stageOneReference(); }
    @Override public void beginSnapshot(ProjectId projectId, SnapshotId snapshotId) { throw new UnsupportedOperationException(); }
    @Override public void writeFacts(SnapshotId snapshotId, Collection<? extends CanonicalFact> facts) { throw new UnsupportedOperationException(); }
    @Override public void sealSnapshot(SnapshotId snapshotId, SnapshotCompletion completion) { throw new UnsupportedOperationException(); }
    @Override public void promotePartial(SnapshotId snapshotId) { throw new UnsupportedOperationException(); }
    @Override public void discardSnapshot(SnapshotId snapshotId) { throw new UnsupportedOperationException(); }
    @Override public void deleteSnapshot(SnapshotId snapshotId) { throw new UnsupportedOperationException(); }
    @Override public SnapshotLease retainSnapshot(SnapshotId snapshotId) { throw new UnsupportedOperationException(); }
    @Override public SnapshotLease retainActiveSnapshot(ProjectId projectId) { throw new UnsupportedOperationException(); }
    @Override public Optional<SnapshotCompletion> sealedSnapshotCompletion(SnapshotId snapshotId) { return Optional.empty(); }
    @Override public Optional<ProjectId> snapshotProject(SnapshotId snapshotId) { return Optional.empty(); }
    @Override public Optional<SnapshotStatus> snapshotStatus(SnapshotId snapshotId) { return Optional.empty(); }
    @Override public Coverage snapshotCoverage(SnapshotId snapshotId) { throw new UnsupportedOperationException(); }
    @Override public Coverage tableImpactCoverage(SnapshotId snapshotId, NodeId tableId, TableImpactResult result) { return snapshotCoverage(snapshotId); }
    @Override public int cleanupSnapshots(ProjectId projectId, int retainNewest) { return 0; }
    @Override public int purgeProject(ProjectId projectId) { return 0; }
    @Override public Optional<SnapshotId> activeSnapshot(ProjectId projectId) { return Optional.empty(); }
    @Override public byte[] exportJsonl(SnapshotId snapshotId) { throw new UnsupportedOperationException(); }
    @Override public Optional<CanonicalFact> findFact(SnapshotId snapshotId, String factKind, String canonicalId) { return Optional.empty(); }
    @Override public TableImpactResult tableImpact(SnapshotId snapshotId, NodeId tableId, QueryBounds bounds) { throw new UnsupportedOperationException(); }
}
