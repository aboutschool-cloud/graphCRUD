package io.graphcrud.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.graphcrud.application.GraphStore;
import io.graphcrud.application.ProjectId;
import io.graphcrud.application.SnapshotCompletion;
import io.graphcrud.model.SnapshotId;
import io.graphcrud.model.NodeId;
import io.graphcrud.model.NodeKind;
import io.graphcrud.application.QueryBounds;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryGraphStorePartialPromotionContractTest {
    @Test
    void partial_snapshot_requires_explicit_promotion() {
        GraphStore store = new InMemoryGraphStore();
        var projectId = new ProjectId("billing");
        var complete = new SnapshotId("snapshot-complete");
        var partial = new SnapshotId("snapshot-partial");

        store.beginSnapshot(projectId, complete);
        store.sealSnapshot(complete, SnapshotCompletion.COMPLETE);
        store.beginSnapshot(projectId, partial);
        store.sealSnapshot(partial, SnapshotCompletion.PARTIAL);

        assertEquals(complete, store.activeSnapshot(projectId).orElseThrow());

        store.promotePartial(partial);

        assertEquals(partial, store.activeSnapshot(projectId).orElseThrow());
    }

    @Test
    void sealed_partial_snapshot_is_queryable_by_id_and_reports_its_state() {
        GraphStore store = new InMemoryGraphStore();
        var partial = new SnapshotId("snapshot-partial");
        var table = NodeId.of(NodeKind.TABLE, Map.of("database", "orders", "name", "purchase_order"));

        store.beginSnapshot(new ProjectId("billing"), partial);
        store.sealSnapshot(partial, SnapshotCompletion.PARTIAL);

        var result = store.tableImpact(partial, table, new QueryBounds(12, 100));

        assertEquals(partial, result.snapshotId());
        assertEquals(SnapshotCompletion.PARTIAL, result.snapshotCompletion());
    }
}
