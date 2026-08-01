package io.graphcrud.application;

import java.util.Objects;
import java.util.Set;

public record PostgreSqlContext(String databaseSource, String defaultSchema, Set<String> declaredTables) {
    public PostgreSqlContext(String databaseSource, String defaultSchema) {
        this(databaseSource, defaultSchema, Set.of());
    }

    public PostgreSqlContext {
        Objects.requireNonNull(databaseSource, "databaseSource");
        Objects.requireNonNull(defaultSchema, "defaultSchema");
        declaredTables = Set.copyOf(declaredTables);
        if (databaseSource.isBlank() || defaultSchema.isBlank()) {
            throw new IllegalArgumentException("Database Source and default schema are required");
        }
    }
}
