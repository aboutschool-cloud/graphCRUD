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

    Optional<SnapshotId> activeSnapshot(ProjectId projectId);

    byte[] exportJsonl(SnapshotId snapshotId);

    TableImpactResult tableImpact(SnapshotId snapshotId, NodeId tableId, QueryBounds bounds);
}
