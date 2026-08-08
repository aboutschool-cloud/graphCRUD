package io.graphcrud.launcher;

/** Machine-readable timing event written separately from semantic CLI output. */
public record StageTimingReport(long discoveryMs, long parsingMs, long resolutionMs) {
    public StageTimingReport {
        if (discoveryMs < 0 || parsingMs < 0 || resolutionMs < 0) {
            throw new IllegalArgumentException("stage timings must be non-negative");
        }
    }

    public String toJson() {
        return "{\"event\":\"analysis-stage-timing\",\"discoveryMs\":" + discoveryMs
                + ",\"parsingMs\":" + parsingMs + ",\"resolutionMs\":" + resolutionMs + "}";
    }
}
