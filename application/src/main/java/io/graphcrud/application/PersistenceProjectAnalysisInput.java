package io.graphcrud.application;

import io.graphcrud.model.CanonicalFact;
import io.graphcrud.model.SnapshotId;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record PersistenceProjectAnalysisInput(
        ProjectId projectId,
        SnapshotId snapshotId,
        Path projectRoot,
        String moduleName,
        String databaseSource,
        String defaultSchema,
        Optional<String> databaseId,
        String targetEnvironment,
        List<SchemaSource> schemaSources,
        List<CanonicalFact> javaFacts) {
    public PersistenceProjectAnalysisInput {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(moduleName, "moduleName");
        Objects.requireNonNull(databaseSource, "databaseSource");
        Objects.requireNonNull(defaultSchema, "defaultSchema");
        Objects.requireNonNull(targetEnvironment, "targetEnvironment");
        databaseId = databaseId == null ? Optional.empty() : databaseId;
        schemaSources = List.copyOf(schemaSources);
        javaFacts = List.copyOf(javaFacts);
        if (targetEnvironment.isBlank()) {
            throw new IllegalArgumentException("target environment is required");
        }
    }
}
