package io.graphcrud.application;

import io.graphcrud.model.SnapshotId;
import java.util.Objects;

public record AnalysisJobRequest(
        ProjectId projectId,
        SnapshotId snapshotId,
        CanonicalFactAnalysis analysis,
        int batchSize) {
    public AnalysisJobRequest {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(analysis, "analysis");
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be positive");
    }
}
