package io.graphcrud.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.graphcrud.model.NodeFact;
import io.graphcrud.model.NodeId;
import io.graphcrud.model.NodeKind;
import io.graphcrud.model.SnapshotId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DefaultAnalysisJobRunnerContractTest {
    @Test
    void complete_job_batches_facts_and_promotes_only_after_sealing() {
        var store = new RecordingStore();
        var runner = new DefaultAnalysisJobRunner(store);
        var result = runner.run(request(SnapshotCompletion.COMPLETE), CancellationToken.NEVER);

        assertEquals(AnalysisJobState.SUCCEEDED, result.state());
        assertEquals(List.of("begin", "write:2", "write:1", "seal:COMPLETE"), store.operations);
        assertEquals(List.of(AnalysisJobState.QUEUED, AnalysisJobState.RUNNING, AnalysisJobState.RUNNING,
                AnalysisJobState.RUNNING, AnalysisJobState.SEALING, AnalysisJobState.SUCCEEDED),
                result.progress().stream().map(AnalysisProgress::state).toList());
    }

    @Test
    void cancellation_discards_staging_and_preserves_active_snapshot() {
        var store = new RecordingStore();
        var runner = new DefaultAnalysisJobRunner(store);
        var checks = new int[] {0};
        var result = runner.run(request(SnapshotCompletion.COMPLETE), () -> ++checks[0] == 4);

        assertEquals(AnalysisJobState.CANCELLED, result.state());
        assertEquals(List.of("begin", "write:2", "discard"), store.operations);
    }

    @Test
    void partial_job_seals_without_implicit_promotion() {
        var store = new RecordingStore();
        var result = new DefaultAnalysisJobRunner(store)
                .run(request(SnapshotCompletion.PARTIAL), CancellationToken.NEVER);

        assertEquals(AnalysisJobState.PARTIAL, result.state());
        assertEquals(List.of("begin", "write:2", "write:1", "seal:PARTIAL"), store.operations);
    }

    @Test
    void analysis_work_can_cooperatively_cancel_before_staging() {
        var store = new RecordingStore();
        var cancelled = new boolean[] {false};
        var request = new AnalysisJobRequest(new ProjectId("orders"), new SnapshotId("next"), token -> {
            cancelled[0] = true;
            return new CanonicalFactAnalysisResult(List.of(), SnapshotCompletion.COMPLETE);
        }, 2);
        var result = new DefaultAnalysisJobRunner(store).run(request, () -> cancelled[0]);
        assertEquals(AnalysisJobState.CANCELLED, result.state());
        assertTrue(store.operations.isEmpty());
    }

    @Test
    void write_failure_discards_staging_and_reports_failed() {
        var store = new RecordingStore();
        store.failWrite = true;
        var result = new DefaultAnalysisJobRunner(store).run(request(SnapshotCompletion.COMPLETE), CancellationToken.NEVER);
        assertEquals(AnalysisJobState.FAILED, result.state());
        assertEquals(List.of("begin", "write:2", "discard"), store.operations);
    }

    @Test
    void commit_ambiguous_seal_is_reconciled_instead_of_reported_failed() {
        var store = new RecordingStore();
        store.ambiguousSeal = true;
        var result = new DefaultAnalysisJobRunner(store).run(request(SnapshotCompletion.COMPLETE), CancellationToken.NEVER);
        assertEquals(AnalysisJobState.SUCCEEDED, result.state());
        assertTrue(result.progress().getLast().message().contains("reconciled"));
    }

    @Test
    void seal_and_reconciliation_failure_reports_failed_when_staging_cleanup_succeeds() {
        var store = new RecordingStore();
        store.failSealBeforeCommit = true;
        store.failReconciliation = true;
        var result = new DefaultAnalysisJobRunner(store).run(request(SnapshotCompletion.COMPLETE), CancellationToken.NEVER);
        assertEquals(AnalysisJobState.FAILED, result.state());
        assertEquals("discard", store.operations.getLast());
    }

    @Test
    void committed_but_unavailable_seal_reports_reconciliation_required() {
        var store = new RecordingStore();
        store.ambiguousSeal = true;
        store.failReconciliation = true;
        var result = new DefaultAnalysisJobRunner(store).run(request(SnapshotCompletion.COMPLETE), CancellationToken.NEVER);
        assertEquals(AnalysisJobState.RECONCILIATION_REQUIRED, result.state());
    }

    private static AnalysisJobRequest request(SnapshotCompletion completion) {
        var facts = List.of(node("a"), node("b"), node("c"));
        return new AnalysisJobRequest(new ProjectId("orders"), new SnapshotId("next"),
                ignored -> new CanonicalFactAnalysisResult(facts, completion), 2);
    }

    private static NodeFact node(String name) {
        return new NodeFact(NodeId.of(NodeKind.TABLE, Map.of("name", name)), Map.of());
    }

    private static final class RecordingStore implements GraphStore {
        private final java.util.ArrayList<String> operations = new java.util.ArrayList<>();
        private boolean failWrite;
        private boolean ambiguousSeal;
        private boolean failSealBeforeCommit;
        private boolean failReconciliation;
        private SnapshotCompletion sealed;
        @Override public GraphStoreCapabilities capabilities() { return GraphStoreCapabilities.stageOneReference(); }
        @Override public void beginSnapshot(ProjectId projectId, SnapshotId snapshotId) { operations.add("begin"); }
        @Override public void writeFacts(SnapshotId id, Collection<? extends io.graphcrud.model.CanonicalFact> facts) { operations.add("write:" + facts.size()); if (failWrite) throw new IllegalStateException("write failed"); }
        @Override public void sealSnapshot(SnapshotId id, SnapshotCompletion completion) { operations.add("seal:" + completion); if (failSealBeforeCommit) throw new IllegalStateException("seal failed"); sealed = completion; if (ambiguousSeal) throw new IllegalStateException("commit outcome unknown"); }
        @Override public void promotePartial(SnapshotId id) { operations.add("promote"); }
        @Override public void discardSnapshot(SnapshotId id) { if (sealed != null) throw new IllegalStateException("already sealed"); operations.add("discard"); }
        @Override public void deleteSnapshot(SnapshotId id) { operations.add("delete"); }
        @Override public SnapshotLease retainSnapshot(SnapshotId id) { throw new UnsupportedOperationException(); }
        @Override public SnapshotLease retainActiveSnapshot(ProjectId id) { throw new UnsupportedOperationException(); }
        @Override public Optional<SnapshotCompletion> sealedSnapshotCompletion(SnapshotId id) { if (failReconciliation) throw new IllegalStateException("backend unavailable"); return Optional.ofNullable(sealed); }
        @Override public Optional<ProjectId> snapshotProject(SnapshotId id) { return Optional.empty(); }
        @Override public Optional<SnapshotStatus> snapshotStatus(SnapshotId id) { return Optional.empty(); }
        @Override public Coverage snapshotCoverage(SnapshotId id) { return Coverage.forCompletion(sealed == null ? SnapshotCompletion.PARTIAL : sealed); }
        @Override public Coverage tableImpactCoverage(SnapshotId id, NodeId tableId, TableImpactResult result) { return snapshotCoverage(id); }
        @Override public int cleanupSnapshots(ProjectId id, int retainNewest) { return 0; }
        @Override public int purgeProject(ProjectId id) { return 0; }
        @Override public Optional<SnapshotId> activeSnapshot(ProjectId id) { return Optional.empty(); }
        @Override public byte[] exportJsonl(SnapshotId id) { throw new UnsupportedOperationException(); }
        @Override public Optional<io.graphcrud.model.CanonicalFact> findFact(SnapshotId id, String kind, String canonicalId) { return Optional.empty(); }
        @Override public TableImpactResult tableImpact(SnapshotId id, NodeId tableId, QueryBounds bounds) { throw new UnsupportedOperationException(); }
    }
}
