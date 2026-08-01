package io.graphcrud.application;

public interface AnalysisJobRunner {
    AnalysisJobResult run(AnalysisJobRequest request, CancellationToken cancellationToken);

    default AnalysisJobResult run(AnalysisJobRequest request, CancellationToken cancellationToken,
                                  java.util.function.Consumer<AnalysisProgress> progressListener) {
        var result = run(request, cancellationToken);
        result.progress().forEach(progressListener);
        return result;
    }
}
