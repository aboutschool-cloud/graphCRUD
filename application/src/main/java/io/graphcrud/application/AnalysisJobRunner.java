package io.graphcrud.application;

public interface AnalysisJobRunner {
    AnalysisJobResult run(AnalysisJobRequest request, CancellationToken cancellationToken);
}
