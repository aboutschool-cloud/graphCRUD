package io.graphcrud.model;

public sealed interface EvidenceSubject permits NodeId, RelationshipAssertionId {
    String subjectKind();

    String subjectId();
}
