package io.graphcrud.application;

import io.graphcrud.model.SnapshotId;
import java.util.List;
import java.util.Objects;

public record TableImpactResult(
        SnapshotId snapshotId,
        SnapshotCompletion snapshotCompletion,
        List<ImpactPath> paths,
        boolean truncated) {
    public TableImpactResult {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(snapshotCompletion, "snapshotCompletion");
        paths = List.copyOf(paths);
    }
}
