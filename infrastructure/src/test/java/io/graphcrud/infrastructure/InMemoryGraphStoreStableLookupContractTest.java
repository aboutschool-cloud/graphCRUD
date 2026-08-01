package io.graphcrud.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.graphcrud.application.GraphStore;
import io.graphcrud.application.ProjectId;
import io.graphcrud.application.SnapshotCompletion;
import io.graphcrud.model.NodeFact;
import io.graphcrud.model.NodeId;
import io.graphcrud.model.NodeKind;
import io.graphcrud.model.SnapshotId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryGraphStoreStableLookupContractTest {
    @Test
    void sealed_snapshot_supports_stable_canonical_fact_lookup() {
        GraphStore store = new InMemoryGraphStore();
        var snapshotId = new SnapshotId("snapshot-lookup");
        var table = new NodeFact(
                NodeId.of(NodeKind.TABLE, Map.of("table", "orders")),
                Map.of("displayName", "orders"));

        store.beginSnapshot(new ProjectId("billing"), snapshotId);
        store.writeFacts(snapshotId, List.of(table));
        store.sealSnapshot(snapshotId, SnapshotCompletion.COMPLETE);

        assertEquals(table, store.findFact(snapshotId, table.factKind(), table.canonicalId()).orElseThrow());
        assertTrue(store.findFact(snapshotId, table.factKind(), "missing").isEmpty());
    }
}
