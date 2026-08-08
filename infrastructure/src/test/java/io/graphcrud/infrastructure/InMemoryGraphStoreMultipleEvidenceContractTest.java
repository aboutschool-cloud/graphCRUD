package io.graphcrud.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.graphcrud.application.CrudOperation;
import io.graphcrud.application.GraphStore;
import io.graphcrud.application.ImpactPath;
import io.graphcrud.application.ProjectId;
import io.graphcrud.application.QueryBounds;
import io.graphcrud.application.SnapshotCompletion;
import io.graphcrud.model.EvidenceLevel;
import io.graphcrud.model.EvidenceOccurrence;
import io.graphcrud.model.NodeId;
import io.graphcrud.model.NodeKind;
import io.graphcrud.model.RelationshipAssertion;
import io.graphcrud.model.RelationshipType;
import io.graphcrud.model.SnapshotId;
import io.graphcrud.model.SourceAnchor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryGraphStoreMultipleEvidenceContractTest {
    @Test
    void every_confirmed_source_occurrence_remains_visible_in_evidence_paths() {
        GraphStore store = new InMemoryGraphStore();
        var snapshot = new SnapshotId("multiple-evidence");
        var sql = NodeId.of(NodeKind.SQL_STATEMENT, Map.of("statement", "select-order"));
        var table = NodeId.of(NodeKind.TABLE, Map.of("database", "orders", "name", "purchase_order"));
        var reads = RelationshipAssertion.of(sql, RelationshipType.READS, table, Map.of());
        var first = EvidenceOccurrence.of(
                reads.id(), snapshot, "hand-authored", new SourceAnchor("fixture.facts", 10, 1),
                EvidenceLevel.CONFIRMED, "First call site.");
        var second = EvidenceOccurrence.of(
                reads.id(), snapshot, "hand-authored", new SourceAnchor("fixture.facts", 20, 1),
                EvidenceLevel.CONFIRMED, "Second call site.");

        store.beginSnapshot(new ProjectId("orders"), snapshot);
        store.writeFacts(snapshot, List.of(second, reads, first));
        store.sealSnapshot(snapshot, SnapshotCompletion.COMPLETE);

        assertEquals(List.of(
                new ImpactPath(CrudOperation.READS, List.of(sql, table), List.of(first.id())),
                new ImpactPath(CrudOperation.READS, List.of(sql, table), List.of(second.id()))),
                store.tableImpact(snapshot, table, new QueryBounds(12, 100)).paths());
    }

    @Test
    void path_limit_reports_explicit_truncation() {
        GraphStore store = new InMemoryGraphStore();
        var snapshot = new SnapshotId("truncated-evidence");
        var sql = NodeId.of(NodeKind.SQL_STATEMENT, Map.of("statement", "select-order"));
        var table = NodeId.of(NodeKind.TABLE, Map.of("database", "orders", "name", "purchase_order"));
        var firstRead = RelationshipAssertion.of(
                sql, RelationshipType.READS, table, Map.of("branch", "first"));
        var secondRead = RelationshipAssertion.of(
                sql, RelationshipType.READS, table, Map.of("branch", "second"));
        var first = EvidenceOccurrence.of(
                firstRead.id(), snapshot, "hand-authored", new SourceAnchor("fixture.facts", 10, 1),
                EvidenceLevel.CONFIRMED, "First call site.");
        var second = EvidenceOccurrence.of(
                secondRead.id(), snapshot, "hand-authored", new SourceAnchor("fixture.facts", 20, 1),
                EvidenceLevel.CONFIRMED, "Second call site.");

        store.beginSnapshot(new ProjectId("orders"), snapshot);
        store.writeFacts(snapshot, List.of(firstRead, first, secondRead, second));
        store.sealSnapshot(snapshot, SnapshotCompletion.COMPLETE);

        var result = store.tableImpact(snapshot, table, new QueryBounds(12, 1));

        assertEquals(1, result.paths().size());
        assertTrue(result.truncated());
    }
}
