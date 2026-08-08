package io.graphcrud.application;

import io.graphcrud.model.CanonicalFact;
import java.util.List;

public record CanonicalFactAnalysisResult(List<? extends CanonicalFact> facts, SnapshotCompletion completion) {
    public CanonicalFactAnalysisResult {
        facts = List.copyOf(facts);
    }
}
