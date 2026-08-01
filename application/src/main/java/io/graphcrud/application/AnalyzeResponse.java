package io.graphcrud.application;

import io.graphcrud.model.SnapshotId;

public record AnalyzeResponse(String schemaVersion, ProjectId projectId, SnapshotId snapshotId,
                              AnalysisJobResult result, Coverage coverage) {}
