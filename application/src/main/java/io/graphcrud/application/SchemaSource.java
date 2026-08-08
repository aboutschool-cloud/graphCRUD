package io.graphcrud.application;

import java.nio.file.Path;
import java.util.Objects;

public record SchemaSource(Path path, String environment, int priority) {
    public SchemaSource {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        if (!path.isAbsolute() || environment.isBlank()) {
            throw new IllegalArgumentException("Schema Source requires an absolute path and environment");
        }
    }
}
