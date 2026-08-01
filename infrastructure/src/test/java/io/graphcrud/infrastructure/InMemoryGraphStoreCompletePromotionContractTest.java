package io.graphcrud.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.graphcrud.application.GraphStore;
import io.graphcrud.application.GraphStoreCapability;
import io.graphcrud.application.ProjectId;
import io.graphcrud.application.SnapshotCompletion;
import io.graphcrud.model.SnapshotId;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InMemoryGraphStoreCompletePromotionContractTest {
    @Test
    void complete_snapshot_is_atomically_promoted_by_a_capable_store() {
        GraphStore store = new InMemoryGraphStore();
        var projectId = new ProjectId("billing");
        var snapshotId = new SnapshotId("snapshot-complete");

        assertEquals(Set.of(
                GraphStoreCapability.BATCH_WRITE,
                GraphStoreCapability.STABLE_LOOKUP,
                GraphStoreCapability.BOUNDED_TRAVERSAL,
                GraphStoreCapability.SNAPSHOT_SWITCHING,
                GraphStoreCapability.TRANSACTIONAL_PROMOTION), store.capabilities().values());

        store.beginSnapshot(projectId, snapshotId);
        store.sealSnapshot(snapshotId, SnapshotCompletion.COMPLETE);

        assertEquals(snapshotId, store.activeSnapshot(projectId).orElseThrow());
    }

    @Test
    void sealed_snapshot_identity_cannot_be_reused() {
        GraphStore store = new InMemoryGraphStore();
        var projectId = new ProjectId("billing");
        var snapshotId = new SnapshotId("snapshot-complete");

        store.beginSnapshot(projectId, snapshotId);
        store.sealSnapshot(snapshotId, SnapshotCompletion.COMPLETE);

        assertThrows(IllegalStateException.class, () -> store.beginSnapshot(projectId, snapshotId));
    }
}
