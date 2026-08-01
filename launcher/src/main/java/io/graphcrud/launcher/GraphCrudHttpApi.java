package io.graphcrud.launcher;

import io.graphcrud.application.StatusResponse;
import io.graphcrud.application.*;

/** Versioned HTTP projection shared with CLI machine output. */
public final class GraphCrudHttpApi {
    public static final String BASE_PATH = "/api/v1";
    public static final String EVENT_CONTENT_TYPE = "text/event-stream";
    private final DeliveryOperations operations;
    public GraphCrudHttpApi() { this.operations = null; }
    public GraphCrudHttpApi(DeliveryOperations operations) { this.operations = java.util.Objects.requireNonNull(operations); }
    public String renderStatus(StatusResponse response) { return DeliveryJson.status(response); }
    public HttpResult status(ProjectId project, io.graphcrud.model.SnapshotId snapshot) {
        return invoke(() -> DeliveryJson.status(snapshot == null ? operations.status(project) : operations.status(project, snapshot)));
    }
    public HttpResult impact(ProjectId project, io.graphcrud.model.SnapshotId snapshot, io.graphcrud.model.NodeId table, QueryBounds bounds) {
        return invoke(() -> DeliveryJson.impact(operations.tableImpact(project, snapshot, table, bounds)));
    }
    public HttpResult promote(ProjectId project, io.graphcrud.model.SnapshotId snapshot) {
        return invoke(() -> DeliveryJson.operation(operations.promotePartial(project, snapshot)));
    }
    public HttpResult export(ProjectId project, io.graphcrud.model.SnapshotId snapshot) {
        try { var value = operations.export(project, snapshot); return new HttpResult(200, "application/x-ndjson", value.bytes()); }
        catch (DeliveryException failure) { return error(failure); }
    }
    public HttpResult purge(ProjectId project) { return invoke(() -> DeliveryJson.operation(operations.purgeProject(project))); }
    public HttpResult report(StatusResponse response, boolean html) {
        var body = html ? LocalReport.html(response) : LocalReport.json(response);
        return new HttpResult(200, html ? "text/html; charset=utf-8" : "application/json",
                (body + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public HttpResult report(ImpactResponse response, boolean html) {
        var body = html ? LocalReport.html(response) : LocalReport.json(response);
        return new HttpResult(200, html ? "text/html; charset=utf-8" : "application/json",
                (body + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public HttpResult supportBundle(java.util.Map<String, String> approvedDiagnostics) {
        return new HttpResult(200, "application/vnd.graphcrud.support+json", SupportBundle.create(approvedDiagnostics));
    }
    public HttpResult analyze(AnalysisJobRequest request, CancellationToken token) {
        try {
            var value = operations.analyze(request, token);
            var events = new StringBuilder();
            for (var progress : value.result().progress()) events.append(DeliverySse.progress(progress));
            events.append(DeliverySse.result(value));
            return new HttpResult(200, EVENT_CONTENT_TYPE, events.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (DeliveryException failure) { return error(failure); }
    }
    private HttpResult invoke(java.util.function.Supplier<String> action) {
        try { return json(200, action.get()); } catch (DeliveryException failure) { return error(failure); }
    }
    static HttpResult error(DeliveryException failure) {
        var status = failure.code() == DeliveryErrorCode.NOT_FOUND ? 404 : failure.code() == DeliveryErrorCode.CONFLICT ? 409 : 400;
        return error(status, failure.code(), failure.getMessage());
    }
    static HttpResult error(int status, DeliveryErrorCode code, String message) { return json(status, DeliveryJson.error(code, message)); }
    private static HttpResult json(int status, String body) { return new HttpResult(status, "application/json",
            (body + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    public record HttpResult(int status, String contentType, byte[] body) {
        public HttpResult { body = body.clone(); }
        @Override public byte[] body() { return body.clone(); }
        public String bodyText() { return new String(body, java.nio.charset.StandardCharsets.UTF_8); }
    }
}
