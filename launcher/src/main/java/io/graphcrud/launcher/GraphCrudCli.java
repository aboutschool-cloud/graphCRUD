package io.graphcrud.launcher;

import io.graphcrud.application.StatusResponse;
import io.graphcrud.application.*;
import io.graphcrud.model.*;
import java.util.Map;

/** Thin CLI projection; command parsing delegates all behavior to application use cases. */
public final class GraphCrudCli {
    private final DeliveryOperations operations;
    private final AnalysisRequestFactory analysisRequests;
    public GraphCrudCli() { this.operations = null; this.analysisRequests = null; }
    public GraphCrudCli(DeliveryOperations operations) { this(operations, null); }
    public GraphCrudCli(DeliveryOperations operations, AnalysisRequestFactory analysisRequests) {
        this.operations = java.util.Objects.requireNonNull(operations); this.analysisRequests = analysisRequests;
    }
    public String renderStatus(StatusResponse response) { return DeliveryJson.status(response); }
    public CliResult execute(String... args) {
        if (operations == null) throw new IllegalStateException("delivery operations are not configured");
        try {
            if (args.length < 2) return new CliResult(2, "", "usage: <analyze|status|table-impact|promote-partial|export|report|support-bundle|purge-project> <project> ...\n");
            var project = new ProjectId(args[1]);
            return switch (args[0]) {
                case "analyze" -> {
                    if (analysisRequests == null) yield new CliResult(2, "", "analyze is not configured\n");
                    if (args.length < 3) yield new CliResult(2, "", "analyze requires snapshot\n");
                    yield analyze(analysisRequests.create(project, new SnapshotId(args[2]), args), CancellationToken.NEVER);
                }
                case "status" -> ok(DeliveryJson.status(args.length > 2
                        ? operations.status(project, new SnapshotId(args[2])) : operations.status(project)));
                case "table-impact" -> {
                    if (args.length < 5) yield new CliResult(2, "", "table-impact requires database schema table [depth paths] [snapshot]\n");
                    var table = NodeId.of(NodeKind.TABLE, Map.of("databaseSource", args[2], "schema", args[3], "name", args[4]));
                    var bounds = args.length > 6 ? new QueryBounds(Integer.parseInt(args[5]), Integer.parseInt(args[6])) : QueryBounds.defaults();
                    var selected = args.length > 7 && !args[7].equals("-") ? new SnapshotId(args[7]) : null;
                    yield ok(DeliveryJson.impact(operations.tableImpact(project, selected, table,
                            bounds)));
                }
                case "promote-partial" -> args.length < 3 ? new CliResult(2, "", "promote-partial requires snapshot\n")
                        : ok(DeliveryJson.operation(operations.promotePartial(project, new SnapshotId(args[2]))));
                case "export" -> {
                    var value = operations.export(project, args.length > 2 ? new SnapshotId(args[2]) : null);
                    yield new CliResult(0, new String(value.bytes(), java.nio.charset.StandardCharsets.UTF_8), "");
                }
                case "purge-project" -> ok(DeliveryJson.operation(operations.purgeProject(project)));
                case "report" -> {
                    var html = java.util.Arrays.asList(args).contains("html");
                    if (args.length >= 6 && args[2].equals("table-impact")) {
                        var table = NodeId.of(NodeKind.TABLE, Map.of("databaseSource", args[3], "schema", args[4], "name", args[5]));
                        yield report(operations.tableImpact(project, null, table, QueryBounds.defaults()), html);
                    }
                    yield report(operations.status(project), html);
                }
                case "support-bundle" -> {
                    if (args.length < 3) yield new CliResult(2, "", "support-bundle requires approved diagnostic input\n");
                    yield new CliResult(0, new String(supportBundle(Map.of("diagnostic", args[2])), java.nio.charset.StandardCharsets.UTF_8), "");
                }
                default -> new CliResult(2, "", "unknown command\n");
            };
        } catch (DeliveryException failure) { return new CliResult(exit(failure.code()), "", DeliveryJson.error(failure) + "\n"); }
        catch (IllegalArgumentException failure) { return new CliResult(2, "", failure.getMessage() + "\n"); }
    }
    public CliResult analyze(AnalysisJobRequest request, CancellationToken token) {
        try { return ok(DeliveryJson.analyze(operations.analyze(request, token))); }
        catch (DeliveryException failure) { return new CliResult(exit(failure.code()), "", DeliveryJson.error(failure) + "\n"); }
    }
    public CliResult report(StatusResponse response, boolean html) {
        return ok(html ? LocalReport.html(response) : LocalReport.json(response));
    }
    public CliResult report(ImpactResponse response, boolean html) {
        return ok(html ? LocalReport.html(response) : LocalReport.json(response));
    }
    public byte[] supportBundle(Map<String, String> approvedDiagnostics) { return SupportBundle.create(approvedDiagnostics); }
    private static CliResult ok(String value) { return new CliResult(0, value + "\n", ""); }
    private static int exit(DeliveryErrorCode code) { return code == DeliveryErrorCode.NOT_FOUND ? 4 : code == DeliveryErrorCode.CONFLICT ? 3 : 2; }
    public record CliResult(int exitCode, String stdout, String stderr) {}
    @FunctionalInterface public interface AnalysisRequestFactory {
        AnalysisJobRequest create(ProjectId projectId, SnapshotId snapshotId, String[] args);
    }
}
