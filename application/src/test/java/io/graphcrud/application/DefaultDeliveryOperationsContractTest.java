package io.graphcrud.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.graphcrud.model.NodeId;
import io.graphcrud.model.NodeKind;
import io.graphcrud.model.SnapshotId;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultDeliveryOperationsContractTest {
    private FakeStore store;
    private DefaultDeliveryOperations operations;

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        operations = new DefaultDeliveryOperations(store, new DefaultAnalysisJobRunner(store));
    }

    @Test
    void status_query_export_promote_and_purge_share_stable_application_semantics() {
        var project = new ProjectId("orders");
        var partial = new SnapshotId("partial-1");
        store.snapshot = partial;
        store.completion = SnapshotCompletion.PARTIAL;

        var status = operations.status(project);
        assertEquals("v1", status.schemaVersion());
        assertEquals(DeliveryStatus.PARTIAL, status.status());
        assertEquals(partial, status.snapshotId());
        assertFalse(status.coverage().complete());
        assertEquals(partial, operations.status(project, partial).snapshotId());

        var query = operations.tableImpact(project, null,
                NodeId.of(NodeKind.TABLE, Map.of("databaseSource", "main", "schema", "public", "name", "orders")),
                new QueryBounds(3, 10));
        assertEquals(partial, query.snapshotId());
        assertTrue(query.warnings().stream().anyMatch(w -> w.code().equals("PARTIAL_COVERAGE")));
        assertTrue(query.truncated());

        assertEquals("facts\n", new String(operations.export(project, null).bytes(), StandardCharsets.UTF_8));
        store.snapshot = new SnapshotId("old-active");
        assertEquals(partial, operations.promotePartial(project, partial).snapshotId());
        assertEquals(DeliveryErrorCode.CONFLICT, assertThrows(DeliveryException.class,
                () -> operations.promotePartial(project, partial)).code());
        assertEquals(1, operations.purgeProject(project).removedSnapshots());
    }

    @Test
    void analyze_returns_versioned_identity_progress_and_coverage() {
        var project = new ProjectId("orders");
        var snapshot = new SnapshotId("analysis-1");
        var result = operations.analyze(new AnalysisJobRequest(project, snapshot,
                ignored -> new CanonicalFactAnalysisResult(java.util.List.of(), SnapshotCompletion.PARTIAL), 10),
                CancellationToken.NEVER);
        assertEquals("v1", result.schemaVersion());
        assertEquals(project, result.projectId());
        assertEquals(snapshot, result.snapshotId());
        assertEquals(AnalysisJobState.PARTIAL, result.result().state());
        assertFalse(result.coverage().complete());
        assertEquals(AnalysisJobState.PARTIAL, operations.status(project, snapshot).lastJobState());
    }

    @Test
    void missing_active_snapshot_is_a_stable_not_found_error() {
        store.snapshot = null;
        var failure = assertThrows(DeliveryException.class,
                () -> operations.export(new ProjectId("missing"), null));
        assertEquals(DeliveryErrorCode.NOT_FOUND, failure.code());
    }

    @Test
    void status_observes_running_job_and_purge_removes_run_metadata() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        AnalysisJobRunner blocking = new AnalysisJobRunner() {
            @Override public AnalysisJobResult run(AnalysisJobRequest request, CancellationToken token) { throw new UnsupportedOperationException(); }
            @Override public AnalysisJobResult run(AnalysisJobRequest request, CancellationToken token,
                                                    java.util.function.Consumer<AnalysisProgress> listener) {
                var progress = new AnalysisProgress(AnalysisJobState.RUNNING, 0, "running"); listener.accept(progress); started.countDown();
                try { release.await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                return new AnalysisJobResult(AnalysisJobState.FAILED, java.util.List.of(progress));
            }
        };
        var service = new DefaultDeliveryOperations(store, blocking);
        var project = new ProjectId("orders");
        var future = java.util.concurrent.CompletableFuture.runAsync(() -> service.analyze(
                new AnalysisJobRequest(project, new SnapshotId("running"), ignored -> { throw new AssertionError(); }, 1), CancellationToken.NEVER));
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(DeliveryStatus.RUNNING, service.status(project).status());
        release.countDown(); future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        service.purgeProject(project);
        assertEquals(DeliveryStatus.ABSENT, service.status(project).status());
    }

    private static final class FakeStore extends TestGraphStoreAdapter {
        private SnapshotId snapshot;
        private SnapshotCompletion completion;
        @Override public java.util.Optional<SnapshotId> activeSnapshot(ProjectId projectId) { return java.util.Optional.ofNullable(snapshot); }
        @Override public java.util.Optional<SnapshotCompletion> sealedSnapshotCompletion(SnapshotId id) { return java.util.Optional.ofNullable(completion); }
        @Override public java.util.Optional<ProjectId> snapshotProject(SnapshotId id) { return java.util.Optional.of(new ProjectId("orders")); }
        @Override public java.util.Optional<SnapshotStatus> snapshotStatus(SnapshotId id) { return java.util.Optional.of(
                completion == null ? SnapshotStatus.STAGING : completion == SnapshotCompletion.COMPLETE ? SnapshotStatus.COMPLETE : SnapshotStatus.PARTIAL); }
        @Override public Coverage snapshotCoverage(SnapshotId id) { return Coverage.forCompletion(completion); }
        @Override public Coverage tableImpactCoverage(SnapshotId id, NodeId tableId, TableImpactResult result) { return snapshotCoverage(id); }
        @Override public SnapshotLease retainActiveSnapshot(ProjectId projectId) {
            if (snapshot == null) throw new IllegalStateException("project has no active snapshot: " + projectId.value());
            return lease(snapshot);
        }
        @Override public SnapshotLease retainSnapshot(SnapshotId id) { return lease(id); }
        @Override public byte[] exportJsonl(SnapshotId id) { return "facts\n".getBytes(StandardCharsets.UTF_8); }
        @Override public TableImpactResult tableImpact(SnapshotId id, NodeId tableId, QueryBounds bounds) {
            return new TableImpactResult(id, completion, java.util.List.of(), true);
        }
        @Override public void promotePartial(SnapshotId id) { snapshot = id; }
        @Override public void beginSnapshot(ProjectId projectId, SnapshotId id) { snapshot = id; }
        @Override public void writeFacts(SnapshotId id, java.util.Collection<? extends io.graphcrud.model.CanonicalFact> facts) {}
        @Override public void sealSnapshot(SnapshotId id, SnapshotCompletion value) { completion = value; }
        @Override public int purgeProject(ProjectId projectId) { snapshot = null; return 1; }
        private SnapshotLease lease(SnapshotId id) { return new SnapshotLease() {
            @Override public SnapshotId snapshotId() { return id; }
            @Override public void close() {}
        }; }
    }
}
