package io.graphcrud.application;

import io.graphcrud.model.SnapshotId;

/** Pins one sealed snapshot against cleanup for the duration of a read. */
public interface SnapshotLease extends AutoCloseable {
    SnapshotId snapshotId();

    @Override
    void close();
}
