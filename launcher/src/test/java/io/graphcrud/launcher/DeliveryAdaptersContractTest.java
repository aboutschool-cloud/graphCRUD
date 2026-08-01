package io.graphcrud.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.graphcrud.application.Coverage;
import io.graphcrud.application.DeliveryStatus;
import io.graphcrud.application.ProjectId;
import io.graphcrud.application.StatusResponse;
import io.graphcrud.model.SnapshotId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import io.graphcrud.application.*;
import io.graphcrud.model.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;

class DeliveryAdaptersContractTest {
    @Test
    void cli_http_json_and_reports_share_one_stable_projection() {
        var response = new StatusResponse("v1", new ProjectId("orders"), DeliveryStatus.PARTIAL,
                new SnapshotId("s-1"), new Coverage(false, List.of("module:billing"), List.of("missing dependency")));
        var json = DeliveryJson.status(response);
        assertEquals(json, new GraphCrudCli().renderStatus(response));
        assertEquals(json, new GraphCrudHttpApi().renderStatus(response));
        assertTrue(json.contains("\"schemaVersion\":\"v1\""));
        assertTrue(json.contains("\"complete\":false"));
        assertTrue(LocalReport.json(response).contains("module:billing"));
        assertTrue(LocalReport.html(response).contains("Partial coverage"));
    }

    @Test
    void impact_projection_preserves_paths_evidence_coverage_and_warning_messages() {
        var source = NodeId.of(NodeKind.CODE_SYMBOL, Map.of("project", "p", "module", "m", "language", "java",
                "declaringType", "Orders", "methodName", "load", "jvmParameterSignature", "()V"));
        var table = NodeId.of(NodeKind.TABLE, Map.of("name", "orders"));
        var value = new ImpactResponse("v1", new SnapshotId("s"), SnapshotCompletion.PARTIAL,
                List.of(new ImpactPath(CrudOperation.READS, List.of(source, table), List.of(new EvidenceOccurrenceId("e-1")))),
                true, new Coverage(false, List.of("Orders.java"), List.of("missing dependency")),
                List.of(new DeliveryWarning("PARTIAL_COVERAGE", "Relevant source is unresolved.")));
        var json = DeliveryJson.impact(value);
        assertTrue(json.contains("\"evidenceOccurrenceIds\":[\"e-1\"]"));
        assertTrue(json.contains("Orders.java"));
        assertTrue(json.contains("missing dependency"));
        assertTrue(json.contains("Relevant source is unresolved."));
    }

    @Test
    void support_bundle_is_deterministic_and_redacts_forbidden_customer_data() {
        var input = Map.of("error", "password=hunter2 at C:\\customers\\orders.sql SELECT * FROM secret_table",
                "project", "orders", "count", "12");
        var first = SupportBundle.create(input);
        var second = SupportBundle.create(input);
        assertEquals(new String(first, StandardCharsets.UTF_8), new String(second, StandardCharsets.UTF_8));
        var text = new String(first, StandardCharsets.UTF_8);
        assertFalse(text.contains("hunter2"));
        assertFalse(text.contains("customers"));
        assertFalse(text.contains("SELECT"));
        assertFalse(text.contains("secret_table"));
        assertTrue(text.contains("sha256"));
    }

    @Test
    void cli_and_http_execute_the_same_use_cases_with_stable_transport_semantics() {
        var operations = new FakeOperations();
        var cli = new GraphCrudCli(operations, (project, snapshot, args) -> new AnalysisJobRequest(project, snapshot,
                ignored -> new CanonicalFactAnalysisResult(List.of(), SnapshotCompletion.COMPLETE), 10));
        var http = new GraphCrudHttpApi(operations);
        var cliStatus = cli.execute("status", "orders", "s-1");
        var httpStatus = http.status(new ProjectId("orders"), new SnapshotId("s-1"));
        assertEquals(0, cliStatus.exitCode());
        assertEquals(cliStatus.stdout(), httpStatus.bodyText());
        assertEquals(200, httpStatus.status());
        assertTrue(cli.execute("analyze", "orders", "next").stdout().contains("\"state\":\"SUCCEEDED\""));

        var cliPurge = cli.execute("purge-project", "orders");
        var httpPurge = http.purge(new ProjectId("orders"));
        assertEquals(cliPurge.stdout(), httpPurge.bodyText());
        assertEquals(cli.execute("promote-partial", "orders", "s-1").stdout(),
                http.promote(new ProjectId("orders"), new SnapshotId("s-1")).bodyText());
        assertEquals(cli.execute("export", "orders", "s-1").stdout(),
                http.export(new ProjectId("orders"), new SnapshotId("s-1")).bodyText());
        var cliImpact = cli.execute("table-impact", "orders", "main", "public", "orders");
        var table = NodeId.of(NodeKind.TABLE, Map.of("databaseSource", "main", "schema", "public", "name", "orders"));
        assertEquals(cliImpact.stdout(), http.impact(new ProjectId("orders"), null, table, QueryBounds.defaults()).bodyText());
        assertTrue(cli.execute("report", "orders", "html").stdout().contains("Partial coverage"));
        assertFalse(cli.execute("support-bundle", "orders", "password=secret").stdout().contains("secret"));
        var missingPromotion = cli.execute("promote-partial", "orders");
        assertEquals(2, missingPromotion.exitCode());
        assertEquals("promote-partial requires snapshot\n", missingPromotion.stderr());

        var request = new AnalysisJobRequest(new ProjectId("orders"), new SnapshotId("next"),
                ignored -> new CanonicalFactAnalysisResult(List.of(), SnapshotCompletion.COMPLETE), 10);
        var stream = http.analyze(request, CancellationToken.NEVER);
        assertEquals("text/event-stream", stream.contentType());
        assertTrue(stream.bodyText().contains("event: progress"));
        assertTrue(stream.bodyText().contains("\"stage\":\"ANALYSIS\""));
        assertTrue(stream.bodyText().contains("event: result"));
        assertEquals(cli.report(operations.status(), true).stdout(),
                http.report(operations.status(), true).bodyText());
        assertEquals(new String(cli.supportBundle(Map.of("error", "password=secret")), StandardCharsets.UTF_8),
                new String(http.supportBundle(Map.of("error", "password=secret")).body(), StandardCharsets.UTF_8));
    }

    @Test
    void local_http_listener_exposes_versioned_routes_and_no_store_responses() throws Exception {
        try (var server = new GraphCrudHttpServer(new InetSocketAddress("127.0.0.1", 0), new FakeOperations(),
                (project, query, body) -> new AnalysisJobRequest(project, new SnapshotId(query.get("snapshot")),
                        ignored -> new CanonicalFactAnalysisResult(List.of(), SnapshotCompletion.COMPLETE), 10))) {
            server.start();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()
                    + "/api/v1/projects/orders/status?snapshot=s-1")).GET().build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith("application/json"));
            assertEquals("no-store", response.headers().firstValue("cache-control").orElseThrow());
            assertTrue(response.body().contains("\"schemaVersion\":\"v1\""));
            var analyze = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()
                    + "/api/v1/projects/orders/analyze?snapshot=next")).POST(HttpRequest.BodyPublishers.noBody()).build();
            var progress = HttpClient.newHttpClient().send(analyze, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, progress.statusCode());
            assertTrue(progress.headers().firstValue("content-type").orElseThrow().startsWith("text/event-stream"));
            assertTrue(progress.body().contains("event: result"));
            var reportRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()
                    + "/api/v1/projects/orders/report?format=html")).GET().build();
            assertEquals(200, HttpClient.newHttpClient().send(reportRequest, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    private static final class FakeOperations implements DeliveryOperations {
        private StatusResponse status() { return new StatusResponse("v1", new ProjectId("orders"), DeliveryStatus.PARTIAL,
                new SnapshotId("s-1"), Coverage.forCompletion(SnapshotCompletion.PARTIAL)); }
        @Override public AnalyzeResponse analyze(AnalysisJobRequest request, CancellationToken token) {
            return new AnalyzeResponse("v1", request.projectId(), request.snapshotId(),
                    new AnalysisJobResult(AnalysisJobState.SUCCEEDED, List.of(
                            new AnalysisProgress(AnalysisJobState.RUNNING, 0, "started"),
                            new AnalysisProgress(AnalysisJobState.SUCCEEDED, 0, "done"))),
                    Coverage.forCompletion(SnapshotCompletion.COMPLETE));
        }
        @Override public StatusResponse status(ProjectId projectId) { return status(); }
        @Override public StatusResponse status(ProjectId projectId, SnapshotId snapshotId) { return status(); }
        @Override public ImpactResponse tableImpact(ProjectId projectId, SnapshotId snapshotId, NodeId tableId, QueryBounds bounds) {
            return new ImpactResponse("v1", new SnapshotId("s-1"), SnapshotCompletion.PARTIAL, List.of(), true,
                    Coverage.forCompletion(SnapshotCompletion.PARTIAL), List.of(new DeliveryWarning("PARTIAL_COVERAGE", "partial")));
        }
        @Override public OperationResponse promotePartial(ProjectId projectId, SnapshotId snapshotId) { return new OperationResponse("v1", projectId, snapshotId, 0); }
        @Override public ExportResponse export(ProjectId projectId, SnapshotId snapshotId) { return new ExportResponse("v1", new SnapshotId("s-1"), "facts\n".getBytes(StandardCharsets.UTF_8)); }
        @Override public OperationResponse purgeProject(ProjectId projectId) { return new OperationResponse("v1", projectId, null, 2); }
    }
}
