package io.graphcrud.model;

import java.util.Objects;

public record EvidenceOccurrenceId(String value) {
    public EvidenceOccurrenceId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("evidence occurrence id must not be blank");
        }
    }
}
