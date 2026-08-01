package io.graphcrud.launcher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.graphcrud.application.*;
import io.graphcrud.model.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Customer-local HTTP listener for the versioned delivery API. */
public final class GraphCrudHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final GraphCrudHttpApi api;
    private final DeliveryOperations operations;
    private final AnalysisRequestFactory analysisRequests;

    public GraphCrudHttpServer(InetSocketAddress address, DeliveryOperations operations) throws IOException {
        this(address, operations, null);
    }
    public GraphCrudHttpServer(InetSocketAddress address, DeliveryOperations operations,
                               AnalysisRequestFactory analysisRequests) throws IOException {
        server = HttpServer.create(address, 0);
        api = new GraphCrudHttpApi(operations);
        this.operations = operations;
        this.analysisRequests = analysisRequests;
        server.createContext(GraphCrudHttpApi.BASE_PATH + "/projects", this::handle);
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); }

    private void handle(HttpExchange exchange) throws IOException {
        GraphCrudHttpApi.HttpResult result;
        try {
            var suffix = exchange.getRequestURI().getPath().substring((GraphCrudHttpApi.BASE_PATH + "/projects/").length());
            var parts = suffix.split("/");
            if (parts.length < 2) { send(exchange, GraphCrudHttpApi.error(404, DeliveryErrorCode.NOT_FOUND, "route not found")); return; }
            var project = new ProjectId(decode(parts[0]));
            var action = parts[1];
            var query = query(exchange.getRequestURI().getRawQuery());
            var body = exchange.getRequestBody().readAllBytes();
            var snapshot = query.containsKey("snapshot") ? new SnapshotId(query.get("snapshot")) : null;
            if (exchange.getRequestMethod().equals("POST") && action.equals("analyze")) {
                if (analysisRequests == null) send(exchange, unavailable("analysis request factory is not configured"));
                else streamAnalysis(exchange, analysisRequests.create(project, query, body));
                return;
            }
            result = switch (exchange.getRequestMethod() + " " + action) {
                case "GET status" -> api.status(project, snapshot);
                case "GET export" -> api.export(project, snapshot);
                case "DELETE purge" -> api.purge(project);
                case "POST promote" -> api.promote(project, requiredSnapshot(snapshot));
                case "GET report" -> query.containsKey("table")
                        ? api.report(operations.tableImpact(project, snapshot,
                                NodeId.of(NodeKind.TABLE, Map.of("databaseSource", required(query, "database"),
                                        "schema", required(query, "schema"), "name", required(query, "table"))),
                                query.containsKey("depth") || query.containsKey("paths")
                                        ? new QueryBounds(Integer.parseInt(required(query, "depth")), Integer.parseInt(required(query, "paths")))
                                        : QueryBounds.defaults()), "html".equals(query.get("format")))
                        : api.report(snapshot == null ? operations.status(project) : operations.status(project, snapshot),
                                "html".equals(query.get("format")));
                case "POST support-bundle" -> api.supportBundle(Map.of("diagnostic",
                        new String(body, StandardCharsets.UTF_8)));
                case "GET table-impact" -> api.impact(project, snapshot,
                        NodeId.of(NodeKind.TABLE, Map.of("databaseSource", required(query, "database"),
                                "schema", required(query, "schema"), "name", required(query, "table"))),
                        query.containsKey("depth") || query.containsKey("paths")
                                ? new QueryBounds(Integer.parseInt(required(query, "depth")), Integer.parseInt(required(query, "paths")))
                                : QueryBounds.defaults());
                default -> GraphCrudHttpApi.error(404, DeliveryErrorCode.NOT_FOUND, "route not found");
            };
        } catch (IllegalArgumentException failure) {
            result = GraphCrudHttpApi.error(400, DeliveryErrorCode.INVALID_REQUEST, failure.getMessage());
        } catch (DeliveryException failure) {
            result = GraphCrudHttpApi.error(failure);
        }
        send(exchange, result);
    }
    private static void send(HttpExchange exchange, GraphCrudHttpApi.HttpResult result) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", result.contentType());
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        var body = result.body(); exchange.sendResponseHeaders(result.status(), body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }
    private void streamAnalysis(HttpExchange exchange, AnalysisJobRequest request) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", GraphCrudHttpApi.EVENT_CONTENT_TYPE);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, 0);
        try (var output = exchange.getResponseBody()) {
            var response = operations.analyze(request, CancellationToken.NEVER, progress -> {
                try {
                    output.write(DeliverySse.progress(progress).getBytes(StandardCharsets.UTF_8));
                    output.flush();
                } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
            output.write(DeliverySse.result(response).getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }
    private static Map<String, String> query(String raw) {
        var values = new HashMap<String, String>();
        if (raw == null || raw.isBlank()) return values;
        for (var pair : raw.split("&")) { var parts = pair.split("=", 2); values.put(decode(parts[0]), decode(parts.length == 2 ? parts[1] : "")); }
        return values;
    }
    private static String required(Map<String, String> values, String name) {
        var value = values.get(name); if (value == null || value.isBlank()) throw new IllegalArgumentException("missing query parameter: " + name); return value;
    }
    private static SnapshotId requiredSnapshot(SnapshotId value) { if (value == null) throw new IllegalArgumentException("missing query parameter: snapshot"); return value; }
    private static GraphCrudHttpApi.HttpResult unavailable(String message) { return GraphCrudHttpApi.error(503, DeliveryErrorCode.BACKEND_FAILURE, message); }
    private static String decode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
    @FunctionalInterface public interface AnalysisRequestFactory {
        AnalysisJobRequest create(ProjectId projectId, Map<String, String> query, byte[] body);
    }
}
