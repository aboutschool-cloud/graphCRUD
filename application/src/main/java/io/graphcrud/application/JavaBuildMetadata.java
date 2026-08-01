package io.graphcrud.application;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record JavaBuildMetadata(
        String moduleName,
        Map<Path, Charset> sourceRoots,
        List<Path> classpathEntries,
        boolean includeRunningVmBootclasspath,
        Optional<PostgreSqlContext> postgreSqlContext) {
    public JavaBuildMetadata(
            String moduleName,
            Map<Path, Charset> sourceRoots,
            List<Path> classpathEntries,
            boolean includeRunningVmBootclasspath) {
        this(moduleName, sourceRoots, classpathEntries, includeRunningVmBootclasspath, Optional.empty());
    }

    public JavaBuildMetadata {
        Objects.requireNonNull(moduleName, "moduleName");
        Objects.requireNonNull(sourceRoots, "sourceRoots");
        Objects.requireNonNull(classpathEntries, "classpathEntries");
        postgreSqlContext = postgreSqlContext == null ? Optional.empty() : postgreSqlContext;
        if (moduleName.isBlank() || sourceRoots.isEmpty()) {
            throw new IllegalArgumentException("moduleName and sourceRoots are required");
        }
        if (sourceRoots.keySet().stream().anyMatch(path -> !path.isAbsolute())
                || classpathEntries.stream().anyMatch(path -> !path.isAbsolute())) {
            throw new IllegalArgumentException("source roots and classpath entries must be absolute");
        }
        sourceRoots = Map.copyOf(sourceRoots);
        classpathEntries = List.copyOf(classpathEntries);
    }
}
