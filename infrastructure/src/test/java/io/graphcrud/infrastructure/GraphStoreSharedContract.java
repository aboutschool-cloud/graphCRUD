package io.graphcrud.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

abstract class GraphStoreSharedContract {
    protected abstract GraphStore store();

    @Test
    void complete_snapshot_is_atomically_active_and_portably_queryable() {
        var store = store();
        var project = new ProjectId("orders");
        var snapshot = new SnapshotId("complete");
        var table = table("orders");
        store.beginSnapshot(project, snapshot);
        store.writeFacts(snapshot, List.of(table, table));
        assertThrows(IllegalStateException.class, () -> store.exportJsonl(snapshot));
        store.sealSnapshot(snapshot, SnapshotCompletion.COMPLETE);

        assertEquals(snapshot, store.activeSnapshot(project).orElseThrow());
        assertEquals(project, store.snapshotProject(snapshot).orElseThrow());
        try (var lease = store.retainActiveSnapshot(project)) {
            assertEquals(snapshot, lease.snapshotId());
            assertThrows(IllegalStateException.class, () -> store.deleteSnapshot(snapshot));
        }
        assertEquals(table, store.findFact(snapshot, table.factKind(), table.canonicalId()).orElseThrow());
        assertArrayEquals(CanonicalJsonl.write(List.of(table)), store.exportJsonl(snapshot));
        assertTrue(store.capabilities().values().containsAll(io.graphcrud.application.GraphStoreCapabilities
                .stageOneReference().values()));
    }

    @Test
    void staging_snapshot_has_explicit_portable_status() {
        var store = store();
        var project = new ProjectId("staging-project");
        var snapshot = new SnapshotId("staging-snapshot");
        store.beginSnapshot(project, snapshot);
        assertEquals(project, store.snapshotProject(snapshot).orElseThrow());
        assertEquals(io.graphcrud.application.SnapshotStatus.STAGING, store.snapshotStatus(snapshot).orElseThrow());
        assertTrue(store.sealedSnapshotCompletion(snapshot).isEmpty());
        store.discardSnapshot(snapshot);
        assertTrue(store.snapshotStatus(snapshot).isEmpty());
    }

    @Test
    void partial_snapshot_requires_explicit_promotion() {
        var store = store();
        var project = new ProjectId("orders");
        var complete = seal(store, project, "old", SnapshotCompletion.COMPLETE);
        var partial = seal(store, project, "partial", SnapshotCompletion.PARTIAL);
        assertEquals(complete, store.activeSnapshot(project).orElseThrow());
        store.promotePartial(partial);
        assertEquals(partial, store.activeSnapshot(project).orElseThrow());
        assertThrows(IllegalStateException.class, () -> store.promotePartial(partial));
    }

    @Test
    void retained_read_stays_on_one_snapshot_while_active_snapshot_changes() {
        var store = store();
        var project = new ProjectId("orders");
        var old = seal(store, project, "old", SnapshotCompletion.COMPLETE);
        try (var read = store.retainActiveSnapshot(project)) {
            var next = seal(store, project, "next", SnapshotCompletion.COMPLETE);
            assertEquals(next, store.activeSnapshot(project).orElseThrow());
            assertEquals(old, read.snapshotId());
            assertTrue(store.findFact(read.snapshotId(), "node", table("old").canonicalId()).isPresent());
            assertThrows(IllegalStateException.class, () -> store.deleteSnapshot(old));
        }
        store.deleteSnapshot(old);
    }

    @Test
    void retained_read_stays_fixed_during_explicit_partial_promotion() {
        var store = store();
        var project = new ProjectId("orders");
        var old = seal(store, project, "old", SnapshotCompletion.COMPLETE);
        var partial = seal(store, project, "partial", SnapshotCompletion.PARTIAL);
        try (var read = store.retainActiveSnapshot(project)) {
            store.promotePartial(partial);
            assertEquals(old, read.snapshotId());
            assertEquals(partial, store.activeSnapshot(project).orElseThrow());
            assertTrue(store.findFact(read.snapshotId(), "node", table("old").canonicalId()).isPresent());
        }
    }

    @Test
    void retention_cleanup_removes_expired_snapshots_but_protects_active_and_leased() {
        var store = store();
        var project = new ProjectId("orders");
        var oldest = seal(store, project, "oldest", SnapshotCompletion.COMPLETE);
        var retained = seal(store, project, "retained", SnapshotCompletion.COMPLETE);
        var active = seal(store, project, "active", SnapshotCompletion.COMPLETE);
        try (var lease = store.retainSnapshot(retained)) {
            assertEquals(1, store.cleanupSnapshots(project, 1));
            assertThrows(IllegalStateException.class, () -> store.exportJsonl(oldest));
            assertTrue(store.findFact(retained, "node", table("retained").canonicalId()).isPresent());
        }
        assertEquals(1, store.cleanupSnapshots(project, 1));
        assertEquals(active, store.activeSnapshot(project).orElseThrow());
    }

    @Test
    void cleanup_rejects_active_and_retained_snapshots() {
        var store = store();
        var project = new ProjectId("orders");
        var old = seal(store, project, "old", SnapshotCompletion.COMPLETE);
        var active = seal(store, project, "active", SnapshotCompletion.COMPLETE);
        try (var ignored = store.retainSnapshot(old)) {
            assertThrows(IllegalStateException.class, () -> store.deleteSnapshot(old));
        }
        store.deleteSnapshot(old);
        assertThrows(IllegalStateException.class, () -> store.exportJsonl(old));
        assertThrows(IllegalStateException.class, () -> store.deleteSnapshot(active));
    }

    @Test
    void purge_removes_only_the_selected_project_and_refuses_retained_reads() {
        var store = store();
        var orders = new ProjectId("orders-purge");
        var billing = new ProjectId("billing-purge");
        var first = seal(store, orders, "orders-first", SnapshotCompletion.COMPLETE);
        seal(store, orders, "orders-partial", SnapshotCompletion.PARTIAL);
        var other = seal(store, billing, "billing-active", SnapshotCompletion.COMPLETE);
        try (var ignored = store.retainSnapshot(first)) {
            assertThrows(IllegalStateException.class, () -> store.purgeProject(orders));
        }
        assertEquals(2, store.purgeProject(orders));
        assertTrue(store.activeSnapshot(orders).isEmpty());
        assertThrows(IllegalStateException.class, () -> store.exportJsonl(first));
        assertEquals(other, store.activeSnapshot(billing).orElseThrow());
    }

    @Test
    void canonical_relationships_and_evidence_produce_the_same_bounded_impact() {
        var store = store();
        var snapshot = new SnapshotId("impact");
        var project = new ProjectId("orders");
        var method = NodeId.javaMethod("orders", "app", "java", "Orders", "load", "()V");
        var table = table("orders").id();
        var reads = RelationshipAssertion.of(method, RelationshipType.READS, table, Map.of());
        var evidence = EvidenceOccurrence.of(reads.id(), snapshot, "fixture",
                new SourceAnchor("Orders.java", 12, 4), EvidenceLevel.CONFIRMED, "fixture evidence");
        store.beginSnapshot(project, snapshot);
        store.writeFacts(snapshot, List.of(new NodeFact(method, Map.of()), new NodeFact(table, Map.of()), reads, evidence));
        store.sealSnapshot(snapshot, SnapshotCompletion.COMPLETE);

        var result = store.tableImpact(snapshot, table, new io.graphcrud.application.QueryBounds(12, 100));
        assertEquals(SnapshotCompletion.COMPLETE, result.snapshotCompletion());
        assertEquals(1, result.paths().size());
        assertEquals(List.of(method, table), result.paths().getFirst().nodes());
        assertEquals(List.of(evidence.id()), result.paths().getFirst().evidenceOccurrenceIds());
    }

    @Test
    void partial_coverage_reports_sorted_degraded_source_regions_and_reasons() {
        var store = store();
        var project = new ProjectId("coverage-project");
        var snapshot = new SnapshotId("coverage-snapshot");
        var table = table("coverage-table");
        var evidence = EvidenceOccurrence.of(table.id(), snapshot, "fixture",
                new SourceAnchor("src/Orders.java", 8, 2), EvidenceLevel.UNRESOLVED, "missing dependency");
        store.beginSnapshot(project, snapshot);
        store.writeFacts(snapshot, List.of(table, evidence));
        store.sealSnapshot(snapshot, SnapshotCompletion.PARTIAL);
        var coverage = store.snapshotCoverage(snapshot);
        assertEquals(List.of("src/Orders.java"), coverage.affectedRegions());
        assertEquals(List.of("missing dependency"), coverage.reasons());
    }

    @Test
    void impact_coverage_includes_only_degraded_evidence_relevant_to_the_table() {
        var store = store();
        var project = new ProjectId("query-coverage-project");
        var snapshot = new SnapshotId("query-coverage-snapshot");
        var target = table("target");
        var unrelated = table("unrelated");
        var relevantEvidence = EvidenceOccurrence.of(target.id(), snapshot, "fixture",
                new SourceAnchor("Target.java", 1, 1), EvidenceLevel.UNRESOLVED, "target unresolved");
        var unrelatedEvidence = EvidenceOccurrence.of(unrelated.id(), snapshot, "fixture",
                new SourceAnchor("Other.java", 1, 1), EvidenceLevel.UNRESOLVED, "other unresolved");
        store.beginSnapshot(project, snapshot);
        store.writeFacts(snapshot, List.of(target, unrelated, relevantEvidence, unrelatedEvidence));
        store.sealSnapshot(snapshot, SnapshotCompletion.PARTIAL);
        var result = store.tableImpact(snapshot, target.id(), io.graphcrud.application.QueryBounds.defaults());
        var coverage = store.tableImpactCoverage(snapshot, target.id(), result);
        assertEquals(List.of("Target.java"), coverage.affectedRegions());
        assertEquals(List.of("target unresolved"), coverage.reasons());
    }

    private static SnapshotId seal(GraphStore store, ProjectId project, String value, SnapshotCompletion completion) {
        var id = new SnapshotId(value);
        store.beginSnapshot(project, id);
        store.writeFacts(id, List.of(table(value)));
        store.sealSnapshot(id, completion);
        return id;
    }

    private static NodeFact table(String name) {
        return new NodeFact(NodeId.of(NodeKind.TABLE, Map.of("name", name)), Map.of("displayName", name));
    }
}
