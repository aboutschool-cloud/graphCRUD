package io.graphcrud.application;

import io.graphcrud.model.CanonicalFact;
import java.util.List;

public record PostgreSqlAnalysisResult(List<CanonicalFact> facts, SnapshotCompletion completion) {
    public PostgreSqlAnalysisResult {
        facts = List.copyOf(facts);
    }
}
