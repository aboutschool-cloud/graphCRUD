package io.graphcrud.application;

@FunctionalInterface
public interface CanonicalFactAnalysis {
    CanonicalFactAnalysisResult analyze(CancellationToken cancellationToken);
}
