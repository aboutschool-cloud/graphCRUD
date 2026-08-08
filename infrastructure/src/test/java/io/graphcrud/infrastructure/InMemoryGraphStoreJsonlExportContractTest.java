package io.graphcrud.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import io.graphcrud.application.GraphStore;
import io.graphcrud.application.ProjectId;
import io.graphcrud.application.SnapshotCompletion;
import io.graphcrud.model.NodeFact;
import io.graphcrud.model.NodeId;
import io.graphcrud.model.NodeKind;
import io.graphcrud.model.EvidenceLevel;
import io.graphcrud.model.EvidenceOccurrence;
import io.graphcrud.model.RelationshipAssertion;
import io.graphcrud.model.RelationshipType;
import io.graphcrud.model.SourceAnchor;
import io.graphcrud.model.SnapshotId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryGraphStoreJsonlExportContractTest {
    @Test
    void sealed_snapshot_exports_portable_deterministic_jsonl() {
        GraphStore store = new InMemoryGraphStore();
        var snapshot = new SnapshotId("export-1");
        var node = new NodeFact(
                NodeId.of(NodeKind.TABLE, Map.of("database", "orders", "name", "purchase_order")),
                Map.of("displayName", "purchase_order"));

        store.beginSnapshot(new ProjectId("orders"), snapshot);
        store.writeFacts(snapshot, List.of(node));
        store.sealSnapshot(snapshot, SnapshotCompletion.COMPLETE);

        assertEquals(
                "{\"factKind\":\"node\",\"id\":\"TABLE:ZGF0YWJhc2U=b3JkZXJz&bmFtZQ=cHVyY2hhc2Vfb3JkZXI\","
                        + "\"kind\":\"TABLE\",\"properties\":{\"displayName\":\"purchase_order\"}}\n",
                new String(store.exportJsonl(snapshot), StandardCharsets.UTF_8));
    }

    @Test
    void relationship_and_evidence_export_is_independent_of_write_order() {
        var snapshot = new SnapshotId("export-order");
        var caller = NodeId.javaMethod("orders", "app", "java", "Orders", "submit", "()V");
        var callee = NodeId.javaMethod("orders", "app", "java", "Orders", "persist", "()V");
        var assertion = RelationshipAssertion.of(caller, RelationshipType.CALLS, callee, Map.of());
        var occurrence = EvidenceOccurrence.of(
                assertion.id(), snapshot, "hand-authored", new SourceAnchor("fixture.facts", 2, 1),
                EvidenceLevel.CONFIRMED, "Unique call target.");
        GraphStore first = new InMemoryGraphStore();
        GraphStore second = new InMemoryGraphStore();

        first.beginSnapshot(new ProjectId("orders"), snapshot);
        first.writeFacts(snapshot, List.of(assertion, occurrence));
        first.sealSnapshot(snapshot, SnapshotCompletion.COMPLETE);
        second.beginSnapshot(new ProjectId("orders"), snapshot);
        second.writeFacts(snapshot, List.of(occurrence, assertion));
        second.sealSnapshot(snapshot, SnapshotCompletion.COMPLETE);

        assertArrayEquals(first.exportJsonl(snapshot), second.exportJsonl(snapshot));
    }
}
