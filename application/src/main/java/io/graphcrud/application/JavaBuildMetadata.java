package io.graphcrud.application;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record JavaBuildMetadata(
        String moduleName,
        Map<Path, Charset> sourceRoots,
        List<Path> classpathEntries,
        boolean includeRunningVmBootclasspath) {
    public JavaBuildMetadata {
        Objects.requireNonNull(moduleName, "moduleName");
        Objects.requireNonNull(sourceRoots, "sourceRoots");
        Objects.requireNonNull(classpathEntries, "classpathEntries");
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
