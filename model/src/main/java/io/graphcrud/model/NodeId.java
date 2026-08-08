package io.graphcrud.model;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

public record NodeId(NodeKind kind, Map<String, String> identityParts) implements EvidenceSubject {
    private static final Set<String> FORBIDDEN_IDENTITY_PARTS = Set.of(
            "snapshotid", "sourcelocation", "evidencelevel", "commit", "displayname");
    private static final Set<String> JAVA_METHOD_IDENTITY_PARTS = Set.of(
            "project", "module", "language", "declaringType", "methodName", "jvmParameterSignature");

    public NodeId {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(identityParts, "identityParts");
        if (identityParts.isEmpty()) {
            throw new IllegalArgumentException("identityParts must not be empty");
        }
        var sorted = new TreeMap<String, String>();
        identityParts.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || value.isBlank()) {
                throw new IllegalArgumentException("identity parts must have non-blank keys and values");
            }
            var normalizedKey = key.replace("_", "").replace("-", "").toLowerCase(java.util.Locale.ROOT);
            if (FORBIDDEN_IDENTITY_PARTS.contains(normalizedKey)) {
                throw new IllegalArgumentException(key + " is snapshot or display metadata, not logical identity");
            }
            sorted.put(key, value);
        });
        if (kind == NodeKind.CODE_SYMBOL && !sorted.keySet().equals(JAVA_METHOD_IDENTITY_PARTS)) {
            throw new IllegalArgumentException(
                    "CODE_SYMBOL method identity requires exactly " + JAVA_METHOD_IDENTITY_PARTS);
        }
        identityParts = Collections.unmodifiableMap(sorted);
    }

    public static NodeId of(NodeKind kind, Map<String, String> identityParts) {
        return new NodeId(kind, identityParts);
    }

    public static NodeId javaMethod(
            String project,
            String module,
            String language,
            String declaringType,
            String methodName,
            String jvmParameterSignature) {
        return of(NodeKind.CODE_SYMBOL, Map.of(
                "project", project,
                "module", module,
                "language", language,
                "declaringType", declaringType,
                "methodName", methodName,
                "jvmParameterSignature", jvmParameterSignature));
    }

    public String canonicalValue() {
        return kind.name() + ":" + identityParts.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    @Override
    public String subjectKind() {
        return "node";
    }

    @Override
    public String subjectId() {
        return canonicalValue();
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
