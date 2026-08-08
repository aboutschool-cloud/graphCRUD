package io.graphcrud.application;

import io.graphcrud.model.NodeId;
import io.graphcrud.model.SnapshotId;
import io.graphcrud.model.SourceAnchor;
import java.util.Objects;
import java.util.Set;

public record PostgreSqlAnalysisInput(
        ProjectId projectId,
        SnapshotId snapshotId,
        NodeId sqlStatementId,
        String sql,
        String databaseSource,
        String defaultSchema,
        Set<String> declaredTables,
        SourceAnchor sourceAnchor) {
    public PostgreSqlAnalysisInput(
            ProjectId projectId, SnapshotId snapshotId, NodeId sqlStatementId, String sql,
            String databaseSource, String defaultSchema, SourceAnchor sourceAnchor) {
        this(projectId, snapshotId, sqlStatementId, sql, databaseSource, defaultSchema, Set.of(), sourceAnchor);
    }

    public PostgreSqlAnalysisInput {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(sqlStatementId, "sqlStatementId");
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(databaseSource, "databaseSource");
        Objects.requireNonNull(defaultSchema, "defaultSchema");
        declaredTables = Set.copyOf(declaredTables);
        Objects.requireNonNull(sourceAnchor, "sourceAnchor");
        if (sql.isBlank() || databaseSource.isBlank() || defaultSchema.isBlank()) {
            throw new IllegalArgumentException("SQL, Database Source, and default schema are required");
        }
    }
}
