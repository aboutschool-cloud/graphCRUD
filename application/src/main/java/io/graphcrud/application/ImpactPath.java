package io.graphcrud.application;

import io.graphcrud.model.EvidenceOccurrenceId;
import io.graphcrud.model.NodeId;
import java.util.List;
import java.util.Objects;

public record ImpactPath(
        CrudOperation operation,
        List<NodeId> nodes,
        List<EvidenceOccurrenceId> evidenceOccurrenceIds) {
    public ImpactPath {
        Objects.requireNonNull(operation, "operation");
        nodes = List.copyOf(nodes);
        evidenceOccurrenceIds = List.copyOf(evidenceOccurrenceIds);
        if (nodes.size() < 2 || evidenceOccurrenceIds.size() != nodes.size() - 1) {
            throw new IllegalArgumentException("an impact path requires one evidence occurrence per relationship");
        }
    }
}
