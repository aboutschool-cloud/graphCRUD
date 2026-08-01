package io.graphcrud.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record NodeFact(NodeId id, Map<String, String> properties) implements CanonicalFact {
    public NodeFact {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(properties, "properties");
        properties = Collections.unmodifiableMap(new TreeMap<>(properties));
    }

    @Override
    public String factKind() {
        return "node";
    }

    @Override
    public String canonicalId() {
        return id.canonicalValue();
    }
}
