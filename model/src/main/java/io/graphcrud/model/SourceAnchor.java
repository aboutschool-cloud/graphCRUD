package io.graphcrud.model;

import java.util.Objects;

public record SourceAnchor(String path, int line, int column) {
    public SourceAnchor {
        Objects.requireNonNull(path, "path");
        if (path.isBlank() || line < 1 || column < 1) {
            throw new IllegalArgumentException("source anchor requires a path and positive position");
        }
    }

    public String canonicalValue() {
        return path + ":" + line + ":" + column;
    }
}
