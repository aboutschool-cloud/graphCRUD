package io.graphcrud.application;

public record AnalysisProgress(AnalysisJobState state, int factsWritten, String message) {
    public AnalysisProgress {
        if (factsWritten < 0 || message == null || message.isBlank()) {
            throw new IllegalArgumentException("progress requires a non-negative count and message");
        }
    }
}
