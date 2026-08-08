package io.graphcrud.infrastructure;

import io.graphcrud.application.*;
import io.graphcrud.model.*;
import java.util.*;

final class QueryCoverage {
    private QueryCoverage() {}
    static Coverage execute(SnapshotCompletion completion, List<CanonicalFact> facts, NodeId table, TableImpactResult result) {
        if (completion == SnapshotCompletion.COMPLETE) return Coverage.forCompletion(completion);
        var relevantNodes = new HashSet<String>(); relevantNodes.add(table.canonicalValue());
        result.paths().forEach(path -> path.nodes().forEach(node -> relevantNodes.add(node.canonicalValue())));
        var relevantRelationships = facts.stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .filter(rel -> relevantNodes.contains(rel.source().canonicalValue()) || relevantNodes.contains(rel.target().canonicalValue()))
                .map(rel -> rel.id().value()).collect(java.util.stream.Collectors.toSet());
        var degraded = facts.stream().filter(EvidenceOccurrence.class::isInstance).map(EvidenceOccurrence.class::cast)
                .filter(e -> e.evidenceLevel() != EvidenceLevel.CONFIRMED)
                .filter(e -> e.subject().subjectKind().equals("node") ? relevantNodes.contains(e.subject().subjectId())
                        : relevantRelationships.contains(e.subject().subjectId())).toList();
        if (degraded.isEmpty()) return new Coverage(true, List.of(), List.of());
        return new Coverage(false, degraded.stream().map(e -> e.sourceAnchor().path()).distinct().sorted().toList(),
                degraded.stream().map(EvidenceOccurrence::explanation).distinct().sorted().toList());
    }
}
