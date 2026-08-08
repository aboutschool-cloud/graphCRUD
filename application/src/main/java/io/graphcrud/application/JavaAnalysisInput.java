package io.graphcrud.application;

import io.graphcrud.model.SnapshotId;
import java.nio.file.Path;
import java.util.Objects;

public record JavaAnalysisInput(
        ProjectId projectId,
        SnapshotId snapshotId,
        Path projectRoot,
        JavaBuildMetadata buildMetadata) {
    public JavaAnalysisInput {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(buildMetadata, "buildMetadata");
    }
}
