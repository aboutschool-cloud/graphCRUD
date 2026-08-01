package io.graphcrud.application;

import io.graphcrud.model.SnapshotId;

public record ExportResponse(String schemaVersion, SnapshotId snapshotId, byte[] bytes) {
    public ExportResponse { bytes = bytes.clone(); }
    @Override public byte[] bytes() { return bytes.clone(); }
}
