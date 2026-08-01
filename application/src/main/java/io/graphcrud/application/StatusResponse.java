package io.graphcrud.application;

import io.graphcrud.model.SnapshotId;

public record StatusResponse(String schemaVersion, ProjectId projectId, DeliveryStatus status,
                             SnapshotId snapshotId, Coverage coverage, AnalysisJobState lastJobState) {
    public StatusResponse(String schemaVersion, ProjectId projectId, DeliveryStatus status,
                          SnapshotId snapshotId, Coverage coverage) {
        this(schemaVersion, projectId, status, snapshotId, coverage, null);
    }
}
