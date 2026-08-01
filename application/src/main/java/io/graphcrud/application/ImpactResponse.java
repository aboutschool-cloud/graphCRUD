package io.graphcrud.application;

import io.graphcrud.model.SnapshotId;
import java.util.List;

public record ImpactResponse(String schemaVersion, SnapshotId snapshotId, SnapshotCompletion completion,
                             List<ImpactPath> paths, boolean truncated, QueryBounds bounds, Coverage coverage,
                             List<DeliveryWarning> warnings) {
    public ImpactResponse { paths = List.copyOf(paths); warnings = List.copyOf(warnings); }
    public ImpactResponse(String schemaVersion, SnapshotId snapshotId, SnapshotCompletion completion,
                          List<ImpactPath> paths, boolean truncated, Coverage coverage, List<DeliveryWarning> warnings) {
        this(schemaVersion, snapshotId, completion, paths, truncated, QueryBounds.defaults(), coverage, warnings);
    }
}
