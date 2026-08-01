package io.graphcrud.application;

import io.graphcrud.model.SnapshotId;

public record OperationResponse(String schemaVersion, ProjectId projectId, SnapshotId snapshotId,
                                int removedSnapshots) {}
