package io.graphcrud.model;

import java.util.Objects;

public record SnapshotId(String value) {
    public SnapshotId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("snapshot id must not be blank");
        }
    }
}
