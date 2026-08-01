package io.graphcrud.model;

import java.util.Objects;

public record EvidenceOccurrence(
        EvidenceSubject subject,
        SnapshotId snapshotId,
        String adapter,
        SourceAnchor sourceAnchor,
        EvidenceLevel evidenceLevel,
        String explanation) implements CanonicalFact {
    public EvidenceOccurrence {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(sourceAnchor, "sourceAnchor");
        Objects.requireNonNull(evidenceLevel, "evidenceLevel");
        Objects.requireNonNull(explanation, "explanation");
        if (adapter.isBlank() || explanation.isBlank()) {
            throw new IllegalArgumentException("adapter and explanation must not be blank");
        }
    }

    public static EvidenceOccurrence of(
            EvidenceSubject subject,
            SnapshotId snapshotId,
            String adapter,
            SourceAnchor sourceAnchor,
            EvidenceLevel evidenceLevel,
            String explanation) {
        return new EvidenceOccurrence(
                subject, snapshotId, adapter, sourceAnchor, evidenceLevel, explanation);
    }

    public EvidenceOccurrenceId id() {
        return new EvidenceOccurrenceId(String.join("|",
                CanonicalIdentityEncoding.component(subject.subjectKind()),
                CanonicalIdentityEncoding.component(subject.subjectId()),
                CanonicalIdentityEncoding.component(snapshotId.value()),
                CanonicalIdentityEncoding.component(adapter),
                CanonicalIdentityEncoding.component(sourceAnchor.canonicalValue()),
                CanonicalIdentityEncoding.component(evidenceLevel.name()),
                CanonicalIdentityEncoding.component(explanation)));
    }

    @Override
    public String factKind() {
        return "evidenceOccurrence";
    }

    @Override
    public String canonicalId() {
        return id().value();
    }
}
