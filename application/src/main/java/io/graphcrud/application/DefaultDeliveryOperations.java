package io.graphcrud.application;

import io.graphcrud.model.NodeId;
import io.graphcrud.model.SnapshotId;
import java.util.List;

public final class DefaultDeliveryOperations implements DeliveryOperations {
    public static final String SCHEMA_VERSION = "v1";
    private final GraphStore store;
    private final AnalysisJobRunner jobs;
    private final java.util.concurrent.ConcurrentMap<ProjectId, AnalyzeResponse> latestJobs = new java.util.concurrent.ConcurrentHashMap<>();

    public DefaultDeliveryOperations(GraphStore store, AnalysisJobRunner jobs) {
        this.store = java.util.Objects.requireNonNull(store); this.jobs = java.util.Objects.requireNonNull(jobs);
    }

    @Override public AnalyzeResponse analyze(AnalysisJobRequest request, CancellationToken token) {
        return analyze(request, token, ignored -> {});
    }
    @Override public AnalyzeResponse analyze(AnalysisJobRequest request, CancellationToken token,
                                             java.util.function.Consumer<AnalysisProgress> listener) {
        latestJobs.put(request.projectId(), new AnalyzeResponse(SCHEMA_VERSION, request.projectId(), null,
                new AnalysisJobResult(AnalysisJobState.QUEUED, List.of()),
                new Coverage(false, List.of("analysis-job"), List.of("Analysis is queued."))));
        var result = jobs.run(request, token, progress -> {
            latestJobs.put(request.projectId(), new AnalyzeResponse(SCHEMA_VERSION, request.projectId(), null,
                    new AnalysisJobResult(progress.state(), List.of(progress)),
                    new Coverage(false, List.of("analysis-job"), List.of(progress.message()))));
            listener.accept(progress);
        });
        var hasSnapshot = result.state() == AnalysisJobState.SUCCEEDED || result.state() == AnalysisJobState.PARTIAL;
        var coverage = result.state() == AnalysisJobState.SUCCEEDED ? Coverage.forCompletion(SnapshotCompletion.COMPLETE)
                : result.state() == AnalysisJobState.PARTIAL ? store.snapshotCoverage(request.snapshotId())
                : new Coverage(false, List.of("analysis-job"), List.of("Analysis ended with " + result.state() + "."));
        var response = new AnalyzeResponse(SCHEMA_VERSION, request.projectId(), hasSnapshot ? request.snapshotId() : null, result, coverage);
        latestJobs.put(request.projectId(), response);
        return response;
    }

    @Override public StatusResponse status(ProjectId projectId) {
        var active = store.activeSnapshot(projectId);
        var lastJob = latestJobs.get(projectId);
        var jobStatus = lastJob == null ? null : deliveryStatus(lastJob.result().state());
        if (active.isEmpty()) return new StatusResponse(SCHEMA_VERSION, projectId, jobStatus == null ? DeliveryStatus.ABSENT : jobStatus, null,
                new Coverage(false, List.of("project"), List.of("No active snapshot.")),
                lastJob == null ? null : lastJob.result().state());
        var completion = completion(active.orElseThrow());
        return new StatusResponse(SCHEMA_VERSION, projectId,
                jobStatus == DeliveryStatus.FAILED || jobStatus == DeliveryStatus.CANCELLED || jobStatus == DeliveryStatus.RECONCILIATION_REQUIRED
                        ? jobStatus : completion == SnapshotCompletion.COMPLETE ? DeliveryStatus.COMPLETE : DeliveryStatus.PARTIAL,
                active.orElseThrow(), store.snapshotCoverage(active.orElseThrow()), lastJob == null ? null : lastJob.result().state());
    }

    @Override public StatusResponse status(ProjectId projectId, SnapshotId snapshotId) {
        requireOwned(projectId, snapshotId);
        var lifecycle = store.snapshotStatus(snapshotId).orElseThrow(() -> new DeliveryException(DeliveryErrorCode.NOT_FOUND, "snapshot not found: " + snapshotId.value()));
        if (lifecycle == SnapshotStatus.STAGING) return new StatusResponse(SCHEMA_VERSION, projectId, DeliveryStatus.STAGING,
                snapshotId, new Coverage(false, List.of("snapshot"), List.of("Snapshot is staging.")), AnalysisJobState.RUNNING);
        var completion = completion(snapshotId);
        return new StatusResponse(SCHEMA_VERSION, projectId,
                completion == SnapshotCompletion.COMPLETE ? DeliveryStatus.COMPLETE : DeliveryStatus.PARTIAL,
                snapshotId, store.snapshotCoverage(snapshotId), latestJobs.containsKey(projectId)
                        ? latestJobs.get(projectId).result().state() : null);
    }

    @Override public ImpactResponse tableImpact(ProjectId projectId, SnapshotId selected, NodeId tableId, QueryBounds bounds) {
        try (var lease = lease(projectId, selected)) {
            var result = store.tableImpact(lease.snapshotId(), tableId, bounds);
            var coverage = store.tableImpactCoverage(result.snapshotId(), tableId, result);
            var warnings = coverage.complete() ? List.<DeliveryWarning>of() : List.of(
                    new DeliveryWarning("PARTIAL_COVERAGE", "Results may omit paths in affected analysis regions."));
            return new ImpactResponse(SCHEMA_VERSION, result.snapshotId(), result.snapshotCompletion(),
                    result.paths(), result.truncated(), bounds, coverage, warnings);
        } catch (DeliveryException failure) { throw failure; }
        catch (IllegalArgumentException failure) { throw new DeliveryException(DeliveryErrorCode.INVALID_REQUEST, failure.getMessage()); }
        catch (IllegalStateException failure) { throw map(failure); }
    }

    @Override public OperationResponse promotePartial(ProjectId projectId, SnapshotId snapshotId) {
        try { requireOwned(projectId, snapshotId); if (store.activeSnapshot(projectId).filter(snapshotId::equals).isPresent())
                    throw new DeliveryException(DeliveryErrorCode.CONFLICT, "snapshot is already active: " + snapshotId.value());
            store.promotePartial(snapshotId); return new OperationResponse(SCHEMA_VERSION, projectId, snapshotId, 0); }
        catch (IllegalStateException failure) { throw new DeliveryException(DeliveryErrorCode.CONFLICT, failure.getMessage()); }
    }

    @Override public ExportResponse export(ProjectId projectId, SnapshotId selected) {
        try (var lease = lease(projectId, selected)) {
            return new ExportResponse(SCHEMA_VERSION, lease.snapshotId(), store.exportJsonl(lease.snapshotId()));
        } catch (DeliveryException failure) { throw failure; }
        catch (IllegalStateException failure) { throw map(failure); }
    }

    @Override public OperationResponse purgeProject(ProjectId projectId) {
        try { var removed = store.purgeProject(projectId); latestJobs.remove(projectId);
            return new OperationResponse(SCHEMA_VERSION, projectId, null, removed); }
        catch (IllegalStateException failure) { throw new DeliveryException(DeliveryErrorCode.CONFLICT, failure.getMessage()); }
    }

    private SnapshotLease lease(ProjectId projectId, SnapshotId selected) {
        try { if (selected != null) requireOwned(projectId, selected); return selected == null ? store.retainActiveSnapshot(projectId) : store.retainSnapshot(selected); }
        catch (IllegalStateException failure) { throw map(failure); }
    }
    private SnapshotCompletion completion(SnapshotId id) { return store.sealedSnapshotCompletion(id)
            .orElseThrow(() -> new DeliveryException(DeliveryErrorCode.NOT_FOUND, "snapshot is not sealed: " + id.value())); }
    private DeliveryException map(IllegalStateException failure) {
        return new DeliveryException(failure.getMessage().contains("no active") || failure.getMessage().contains("not sealed")
                ? DeliveryErrorCode.NOT_FOUND : DeliveryErrorCode.BACKEND_FAILURE, failure.getMessage());
    }
    private void requireOwned(ProjectId projectId, SnapshotId snapshotId) {
        var owner = store.snapshotProject(snapshotId)
                .orElseThrow(() -> new DeliveryException(DeliveryErrorCode.NOT_FOUND, "snapshot not found: " + snapshotId.value()));
        if (!owner.equals(projectId)) throw new DeliveryException(DeliveryErrorCode.NOT_FOUND, "snapshot not found: " + snapshotId.value());
    }
    private DeliveryStatus deliveryStatus(AnalysisJobState state) { return switch (state) {
        case QUEUED -> DeliveryStatus.QUEUED; case RUNNING, SEALING -> DeliveryStatus.RUNNING;
        case FAILED -> DeliveryStatus.FAILED; case CANCELLED -> DeliveryStatus.CANCELLED;
        case RECONCILIATION_REQUIRED -> DeliveryStatus.RECONCILIATION_REQUIRED;
        case SUCCEEDED -> DeliveryStatus.COMPLETE; case PARTIAL -> DeliveryStatus.PARTIAL;
    }; }
}
