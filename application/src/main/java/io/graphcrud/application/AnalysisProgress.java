package io.graphcrud.application;

public record AnalysisProgress(AnalysisProgressStage stage, AnalysisJobState state, int factsWritten, String message) {
    public AnalysisProgress(AnalysisJobState state, int factsWritten, String message) {
        this(infer(state, message), state, factsWritten, message);
    }
    public AnalysisProgress {
        if (factsWritten < 0 || message == null || message.isBlank()) {
            throw new IllegalArgumentException("progress requires a non-negative count and message");
        }
    }
    private static AnalysisProgressStage infer(AnalysisJobState state, String message) {
        if (state == AnalysisJobState.QUEUED) return AnalysisProgressStage.DISCOVERY;
        if (state == AnalysisJobState.SEALING) return AnalysisProgressStage.SEALING;
        if (state == AnalysisJobState.RUNNING && message != null && message.contains("staged")) return AnalysisProgressStage.GRAPH_WRITE;
        if (state == AnalysisJobState.RUNNING) return AnalysisProgressStage.ANALYSIS;
        return AnalysisProgressStage.COMPLETE;
    }
}
