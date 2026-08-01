package io.graphcrud.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

public record RelationshipAssertion(
        NodeId source,
        RelationshipType type,
        NodeId target,
        Map<String, String> semanticQualifiers) implements CanonicalFact {
    public RelationshipAssertion {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(semanticQualifiers, "semanticQualifiers");
        semanticQualifiers = Collections.unmodifiableMap(new TreeMap<>(semanticQualifiers));
    }

    public static RelationshipAssertion of(
            NodeId source,
            RelationshipType type,
            NodeId target,
            Map<String, String> semanticQualifiers) {
        return new RelationshipAssertion(source, type, target, semanticQualifiers);
    }

    public RelationshipAssertionId id() {
        var qualifiers = semanticQualifiers.entrySet().stream()
                .map(entry -> CanonicalIdentityEncoding.component(entry.getKey())
                        + "=" + CanonicalIdentityEncoding.component(entry.getValue()))
                .collect(Collectors.joining("&"));
        return new RelationshipAssertionId(
                CanonicalIdentityEncoding.component(source.canonicalValue())
                        + "|" + CanonicalIdentityEncoding.component(type.name())
                        + "|" + CanonicalIdentityEncoding.component(target.canonicalValue())
                        + "|" + qualifiers);
    }

    @Override
    public String factKind() {
        return "relationshipAssertion";
    }

    @Override
    public String canonicalId() {
        return id().value();
    }
}
