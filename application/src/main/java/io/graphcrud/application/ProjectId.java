package io.graphcrud.application;

import java.util.Objects;

public record ProjectId(String value) {
    public ProjectId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("project id must not be blank");
        }
    }
}
