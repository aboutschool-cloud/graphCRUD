package io.graphcrud.launcher;

import io.graphcrud.application.StatusResponse;
import java.util.stream.Collectors;

public final class DeliveryJson {
    private DeliveryJson() {}
    public static String status(StatusResponse value) {
        var coverage = value.coverage();
        return "{\"schemaVersion\":\"" + escape(value.schemaVersion()) + "\",\"projectId\":\""
                + escape(value.projectId().value()) + "\",\"status\":\"" + value.status().name()
                + "\",\"snapshotId\":" + (value.snapshotId() == null ? "null" : "\"" + escape(value.snapshotId().value()) + "\"")
                + ",\"coverage\":{\"complete\":" + coverage.complete() + ",\"affectedRegions\":"
                + array(coverage.affectedRegions()) + ",\"reasons\":" + array(coverage.reasons()) + "},\"lastJobState\":"
                + (value.lastJobState() == null ? "null" : "\"" + value.lastJobState().name() + "\"") + "}";
    }
    public static String operation(io.graphcrud.application.OperationResponse value) {
        return "{\"schemaVersion\":\"" + escape(value.schemaVersion()) + "\",\"projectId\":\""
                + escape(value.projectId().value()) + "\",\"snapshotId\":"
                + (value.snapshotId() == null ? "null" : "\"" + escape(value.snapshotId().value()) + "\"")
                + ",\"removedSnapshots\":" + value.removedSnapshots() + "}";
    }
    public static String impact(io.graphcrud.application.ImpactResponse value) {
        return "{\"schemaVersion\":\"" + escape(value.schemaVersion()) + "\",\"snapshotId\":\""
                + escape(value.snapshotId().value()) + "\",\"completion\":\"" + value.completion().name()
                + "\",\"paths\":" + value.paths().stream().map(path -> "{\"operation\":\"" + path.operation().name()
                        + "\",\"nodes\":" + array(path.nodes().stream().map(io.graphcrud.model.NodeId::canonicalValue).toList())
                        + ",\"evidenceOccurrenceIds\":" + array(path.evidenceOccurrenceIds().stream().map(io.graphcrud.model.EvidenceOccurrenceId::value).toList()) + "}")
                        .collect(Collectors.joining(",", "[", "]"))
                + ",\"truncated\":" + value.truncated() + ",\"bounds\":{\"maximumDepth\":" + value.bounds().maximumDepth()
                + ",\"maximumPaths\":" + value.bounds().maximumPaths() + "}"
                + ",\"coverage\":{\"complete\":" + value.coverage().complete() + ",\"affectedRegions\":"
                + array(value.coverage().affectedRegions()) + ",\"reasons\":" + array(value.coverage().reasons()) + "},\"warnings\":"
                + value.warnings().stream().map(w -> "{\"code\":\"" + escape(w.code()) + "\",\"message\":\""
                        + escape(w.message()) + "\"}").collect(Collectors.joining(",", "[", "]")) + "}";
    }
    public static String analyze(io.graphcrud.application.AnalyzeResponse value) {
        return "{\"schemaVersion\":\"" + escape(value.schemaVersion()) + "\",\"projectId\":\""
                + escape(value.projectId().value()) + "\",\"snapshotId\":" + (value.snapshotId() == null ? "null" : "\"" + escape(value.snapshotId().value()) + "\"")
                + ",\"state\":\"" + value.result().state().name() + "\",\"coverage\":{\"complete\":"
                + value.coverage().complete() + ",\"affectedRegions\":" + array(value.coverage().affectedRegions())
                + ",\"reasons\":" + array(value.coverage().reasons()) + "}}";
    }
    public static String error(io.graphcrud.application.DeliveryException value) {
        return error(value.code(), value.getMessage());
    }
    public static String error(io.graphcrud.application.DeliveryErrorCode code, String message) {
        return "{\"schemaVersion\":\"v1\",\"error\":{\"code\":\"" + code.name()
                + "\",\"message\":\"" + escape(message) + "\"}}";
    }
    static String array(java.util.List<String> values) {
        return values.stream().map(v -> "\"" + escape(v) + "\"").collect(Collectors.joining(",", "[", "]"));
    }
    static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\r", "\\r").replace("\n", "\\n"); }
}
