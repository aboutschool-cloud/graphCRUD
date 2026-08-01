package io.graphcrud.application;

import java.util.List;

public record AnalysisJobResult(AnalysisJobState state, List<AnalysisProgress> progress) {
    public AnalysisJobResult {
        progress = List.copyOf(progress);
    }
}
