package io.graphcrud.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.graphcrud.application.CrudOperation;
import io.graphcrud.application.GraphStore;
import io.graphcrud.application.ImpactPath;
import io.graphcrud.application.ProjectId;
import io.graphcrud.application.QueryBounds;
import io.graphcrud.application.SnapshotCompletion;
import io.graphcrud.model.EvidenceLevel;
import io.graphcrud.model.EvidenceOccurrence;
import io.graphcrud.model.NodeFact;
import io.graphcrud.model.NodeId;
import io.graphcrud.model.NodeKind;
import io.graphcrud.model.RelationshipAssertion;
import io.graphcrud.model.RelationshipType;
import io.graphcrud.model.SnapshotId;
import io.graphcrud.model.SourceAnchor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TableImpactFixtureTest {
    @Test
    void table_impact_follows_only_confirmed_hand_authored_evidence() {
        GraphStore store = new InMemoryGraphStore();
        var project = new ProjectId("orders");
        var snapshot = new SnapshotId("fixture-1");
        var entrypoint = NodeId.of(NodeKind.INVOCATION_SOURCE, Map.of("route", "POST /orders"));
        var handler = NodeId.javaMethod("orders", "app", "java", "Orders", "submit", "()V");
        var sql = NodeId.of(NodeKind.SQL_STATEMENT, Map.of("statement", "select-order"));
        var table = NodeId.of(NodeKind.TABLE, Map.of("database", "orders", "name", "purchase_order"));

        var routesTo = RelationshipAssertion.of(entrypoint, RelationshipType.ROUTES_TO, handler, Map.of());
        var executes = RelationshipAssertion.of(handler, RelationshipType.EXECUTES, sql, Map.of());
        var reads = RelationshipAssertion.of(sql, RelationshipType.READS, table, Map.of());
        var possibleInsert = RelationshipAssertion.of(sql, RelationshipType.INSERTS, table, Map.of());
        var routeEvidence = occurrence(routesTo, snapshot, 3, EvidenceLevel.CONFIRMED, "Manual route binding.");
        var executeEvidence = occurrence(executes, snapshot, 8, EvidenceLevel.CONFIRMED, "Manual SQL binding.");
        var readEvidence = occurrence(reads, snapshot, 12, EvidenceLevel.CONFIRMED, "Literal SELECT target.");
        var possibleEvidence = occurrence(
                possibleInsert, snapshot, 13, EvidenceLevel.POSSIBLE, "Profile-dependent INSERT target.");

        store.beginSnapshot(project, snapshot);
        store.writeFacts(snapshot, List.of(
                possibleEvidence, possibleInsert, readEvidence, reads, executeEvidence, executes,
                routeEvidence, routesTo,
                new NodeFact(table, Map.of("displayName", "purchase_order")),
                new NodeFact(sql, Map.of("displayName", "select order")),
                new NodeFact(handler, Map.of("displayName", "submit")),
                new NodeFact(entrypoint, Map.of("displayName", "POST /orders"))));
        store.sealSnapshot(snapshot, SnapshotCompletion.COMPLETE);

        var result = store.tableImpact(snapshot, table, new QueryBounds(12, 100));

        assertEquals(snapshot, result.snapshotId());
        assertFalse(result.truncated());
        assertEquals(List.of(
                new ImpactPath(
                        CrudOperation.READS,
                        List.of(entrypoint, handler, sql, table),
                        List.of(routeEvidence.id(), executeEvidence.id(), readEvidence.id())),
                new ImpactPath(
                        CrudOperation.READS,
                        List.of(sql, table),
                        List.of(readEvidence.id()))), result.paths());
    }

    @Test
    void depth_limit_reports_explicit_truncation_while_preserving_direct_crud() {
        GraphStore store = new InMemoryGraphStore();
        var snapshot = new SnapshotId("depth-limit");
        var caller = NodeId.javaMethod("orders", "app", "java", "Orders", "submit", "()V");
        var sql = NodeId.of(NodeKind.SQL_STATEMENT, Map.of("statement", "select-order"));
        var table = NodeId.of(NodeKind.TABLE, Map.of("database", "orders", "name", "purchase_order"));
        var executes = RelationshipAssertion.of(caller, RelationshipType.EXECUTES, sql, Map.of());
        var reads = RelationshipAssertion.of(sql, RelationshipType.READS, table, Map.of());
        var executeEvidence = occurrence(executes, snapshot, 8, EvidenceLevel.CONFIRMED, "Manual SQL binding.");
        var readEvidence = occurrence(reads, snapshot, 12, EvidenceLevel.CONFIRMED, "Literal SELECT target.");

        store.beginSnapshot(new ProjectId("orders"), snapshot);
        store.writeFacts(snapshot, List.of(executes, executeEvidence, reads, readEvidence));
        store.sealSnapshot(snapshot, SnapshotCompletion.COMPLETE);

        var result = store.tableImpact(snapshot, table, new QueryBounds(1, 100));

        assertEquals(List.of(new ImpactPath(
                CrudOperation.READS, List.of(sql, table), List.of(readEvidence.id()))), result.paths());
        assertTrue(result.truncated());
    }

    private static EvidenceOccurrence occurrence(
            RelationshipAssertion assertion,
            SnapshotId snapshot,
            int line,
            EvidenceLevel level,
            String explanation) {
        return EvidenceOccurrence.of(
                assertion.id(), snapshot, "hand-authored",
                new SourceAnchor("fixtures/table-impact.facts", line, 1), level, explanation);
    }
}
