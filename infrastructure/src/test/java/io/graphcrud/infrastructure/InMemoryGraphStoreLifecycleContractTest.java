package io.graphcrud.infrastructure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.graphcrud.application.ProjectId;
import io.graphcrud.application.SnapshotCompletion;
import io.graphcrud.model.NodeFact;
import io.graphcrud.model.NodeId;
import io.graphcrud.model.NodeKind;
import io.graphcrud.model.SnapshotId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryGraphStoreLifecycleContractTest {
    @Test
    void retained_and_active_snapshots_are_protected_from_cleanup() {
        var store = new InMemoryGraphStore();
        var first = sealed(store, "first", SnapshotCompletion.COMPLETE);
        var second = sealed(store, "second", SnapshotCompletion.COMPLETE);
        try (var ignored = store.retainSnapshot(first)) {
            assertThrows(IllegalStateException.class, () -> store.deleteSnapshot(first));
        }
        store.deleteSnapshot(first);
        assertFalse(store.findFact(second, "node", NodeId.of(NodeKind.TABLE, Map.of("name", "second")).canonicalValue()).isEmpty());
        assertThrows(IllegalStateException.class, () -> store.deleteSnapshot(second));
    }

    @Test
    void abandoned_staging_snapshot_is_not_queryable() {
        var store = new InMemoryGraphStore();
        var snapshot = new SnapshotId("abandoned");
        store.beginSnapshot(new ProjectId("orders"), snapshot);
        store.discardSnapshot(snapshot);
        assertThrows(IllegalStateException.class, () -> store.exportJsonl(snapshot));
    }

    private static SnapshotId sealed(InMemoryGraphStore store, String value, SnapshotCompletion completion) {
        var snapshot = new SnapshotId(value);
        store.beginSnapshot(new ProjectId("orders"), snapshot);
        store.writeFacts(snapshot, List.of(new NodeFact(NodeId.of(NodeKind.TABLE, Map.of("name", value)), Map.of())));
        store.sealSnapshot(snapshot, completion);
        return snapshot;
    }
}
