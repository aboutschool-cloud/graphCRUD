package io.graphcrud.application;

import io.graphcrud.model.CanonicalFact;
import java.util.List;
import java.util.Objects;

public record JavaAnalysisResult(List<CanonicalFact> facts, SnapshotCompletion completion) {
    public JavaAnalysisResult {
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(completion, "completion");
        facts = List.copyOf(facts);
    }
}
