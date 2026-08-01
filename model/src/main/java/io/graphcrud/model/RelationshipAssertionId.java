package io.graphcrud.model;

import java.util.Objects;

public record RelationshipAssertionId(String value) implements EvidenceSubject {
    public RelationshipAssertionId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("relationship assertion id must not be blank");
        }
    }

    @Override
    public String subjectKind() {
        return "relationshipAssertion";
    }

    @Override
    public String subjectId() {
        return value;
    }
}
