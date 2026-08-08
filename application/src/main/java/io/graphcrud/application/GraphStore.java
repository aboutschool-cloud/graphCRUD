package io.graphcrud.application;

import io.graphcrud.model.CanonicalFact;
import io.graphcrud.model.NodeId;
import io.graphcrud.model.SnapshotId;
import java.util.Collection;
import java.util.Optional;

public interface GraphStore {
    GraphStoreCapabilities capabilities();

    void beginSnapshot(ProjectId projectId, SnapshotId snapshotId);

    void writeFacts(SnapshotId snapshotId, Collection<? extends CanonicalFact> facts);

    void sealSnapshot(SnapshotId snapshotId, SnapshotCompletion completion);

    void promotePartial(SnapshotId snapshotId);

    void discardSnapshot(SnapshotId snapshotId);

    void deleteSnapshot(SnapshotId snapshotId);

    SnapshotLease retainSnapshot(SnapshotId snapshotId);

    SnapshotLease retainActiveSnapshot(ProjectId projectId);

    Optional<SnapshotCompletion> sealedSnapshotCompletion(SnapshotId snapshotId);

    Optional<ProjectId> snapshotProject(SnapshotId snapshotId);

    Optional<SnapshotStatus> snapshotStatus(SnapshotId snapshotId);

    Coverage snapshotCoverage(SnapshotId snapshotId);

    Coverage tableImpactCoverage(SnapshotId snapshotId, NodeId tableId, TableImpactResult result);

    int cleanupSnapshots(ProjectId projectId, int retainNewest);

    int purgeProject(ProjectId projectId);

    Optional<SnapshotId> activeSnapshot(ProjectId projectId);

    byte[] exportJsonl(SnapshotId snapshotId);

    Optional<CanonicalFact> findFact(SnapshotId snapshotId, String factKind, String canonicalId);

    TableImpactResult tableImpact(SnapshotId snapshotId, NodeId tableId, QueryBounds bounds);
}
