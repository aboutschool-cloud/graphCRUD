package io.graphcrud.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidenceOccurrenceContractTest {
    @Test
    void separate_source_occurrences_support_one_canonical_relationship_assertion() {
        var caller = NodeId.javaMethod("orders", "app", "java", "Orders", "submit", "()V");
        var callee = NodeId.javaMethod("orders", "app", "java", "Orders", "persist", "()V");
        var assertion = RelationshipAssertion.of(caller, RelationshipType.CALLS, callee, Map.of());

        var firstCallSite = EvidenceOccurrence.of(
                assertion.id(), new SnapshotId("snapshot-1"), "hand-authored",
                new SourceAnchor("src/Orders.java", 14, 9), EvidenceLevel.CONFIRMED,
                "The call target is unique.");
        var secondCallSite = EvidenceOccurrence.of(
                assertion.id(), new SnapshotId("snapshot-1"), "hand-authored",
                new SourceAnchor("src/Orders.java", 27, 9), EvidenceLevel.POSSIBLE,
                "A bounded alternative also calls the method.");

        assertEquals(assertion.id(), firstCallSite.subject());
        assertEquals(assertion.id(), secondCallSite.subject());
        assertNotEquals(firstCallSite.id(), secondCallSite.id());
    }

    @Test
    void a_canonical_node_has_separate_snapshot_specific_evidence() {
        var node = new NodeFact(
                NodeId.javaMethod("orders", "app", "java", "Orders", "submit", "()V"),
                Map.of("displayName", "submit"));

        var occurrence = EvidenceOccurrence.of(
                node.id(), new SnapshotId("snapshot-1"), "hand-authored",
                new SourceAnchor("src/Orders.java", 7, 1), EvidenceLevel.CONFIRMED,
                "Method declaration.");

        assertEquals(node.id(), occurrence.subject());
    }

    @Test
    void occurrence_identity_distinguishes_field_delimiters_from_field_boundaries() {
        var subject = NodeId.of(NodeKind.TABLE, Map.of("table", "orders"));
        var delimiterInAdapter = EvidenceOccurrence.of(
                subject, new SnapshotId("snapshot-1"), "a|b",
                new SourceAnchor("c", 1, 1), EvidenceLevel.CONFIRMED, "evidence");
        var delimiterInPath = EvidenceOccurrence.of(
                subject, new SnapshotId("snapshot-1"), "a",
                new SourceAnchor("b|c", 1, 1), EvidenceLevel.CONFIRMED, "evidence");

        assertNotEquals(delimiterInAdapter.id(), delimiterInPath.id());
    }
}
