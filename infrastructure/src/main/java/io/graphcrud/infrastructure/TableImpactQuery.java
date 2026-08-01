package io.graphcrud.infrastructure;

import io.graphcrud.application.CrudOperation;
import io.graphcrud.application.ImpactPath;
import io.graphcrud.application.QueryBounds;
import io.graphcrud.application.SnapshotCompletion;
import io.graphcrud.application.TableImpactResult;
import io.graphcrud.model.CanonicalFact;
import io.graphcrud.model.EvidenceLevel;
import io.graphcrud.model.EvidenceOccurrence;
import io.graphcrud.model.EvidenceOccurrenceId;
import io.graphcrud.model.NodeId;
import io.graphcrud.model.RelationshipAssertion;
import io.graphcrud.model.RelationshipAssertionId;
import io.graphcrud.model.RelationshipType;
import io.graphcrud.model.SnapshotId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class TableImpactQuery {
    private TableImpactQuery() {}

    static TableImpactResult execute(
            SnapshotId snapshotId,
            SnapshotCompletion snapshotCompletion,
            List<CanonicalFact> facts,
            NodeId tableId,
            QueryBounds bounds) {
        var assertions = new HashMap<RelationshipAssertionId, RelationshipAssertion>();
        var confirmedEvidence = new HashMap<RelationshipAssertionId, List<EvidenceOccurrence>>();
        for (var fact : facts) {
            if (fact instanceof RelationshipAssertion assertion) {
                assertions.put(assertion.id(), assertion);
            } else if (fact instanceof EvidenceOccurrence occurrence
                    && occurrence.snapshotId().equals(snapshotId)
                    && occurrence.evidenceLevel() == EvidenceLevel.CONFIRMED
                    && occurrence.subject() instanceof RelationshipAssertionId relationshipAssertionId) {
                confirmedEvidence.computeIfAbsent(relationshipAssertionId, ignored -> new ArrayList<>())
                        .add(occurrence);
            }
        }
        confirmedEvidence.values().forEach(occurrences ->
                occurrences.sort(Comparator.comparing(occurrence -> occurrence.id().value())));

        var supported = assertions.values().stream()
                .filter(assertion -> confirmedEvidence.containsKey(assertion.id()))
                .sorted(Comparator.comparing(assertion -> assertion.id().value()))
                .toList();
        var paths = new ArrayList<ImpactPath>();
        boolean depthTruncated = false;
        var detectionBounds = new QueryBounds(
                bounds.maximumDepth(),
                bounds.maximumPaths() == Integer.MAX_VALUE ? Integer.MAX_VALUE : bounds.maximumPaths() + 1);
        for (var crud : supported) {
            var operation = operationOf(crud.type());
            if (operation.isEmpty() || !crud.target().equals(tableId)) {
                continue;
            }
            for (var crudOccurrence : confirmedEvidence.get(crud.id())) {
                if (paths.size() >= detectionBounds.maximumPaths()) {
                    break;
                }
                paths.add(new ImpactPath(
                        operation.orElseThrow(),
                        List.of(crud.source(), tableId),
                        List.of(crudOccurrence.id())));
                boolean hasIncoming = supported.stream()
                        .anyMatch(assertion -> assertion.target().equals(crud.source())
                                && operationOf(assertion.type()).isEmpty());
                if (hasIncoming && paths.size() < detectionBounds.maximumPaths()) {
                    depthTruncated |= collectPaths(
                            crud.source(), operation.orElseThrow(), supported, confirmedEvidence,
                            new ArrayList<>(List.of(tableId)), new ArrayList<>(List.of(crudOccurrence.id())),
                            new HashSet<>(Set.of(tableId)), detectionBounds, paths);
                }
            }
            if (paths.size() >= detectionBounds.maximumPaths()) {
                break;
            }
        }
        paths.sort(Comparator.comparing((ImpactPath path) -> path.nodes().stream()
                        .map(NodeId::canonicalValue).reduce("", (left, right) -> left + "|" + right))
                .thenComparing(path -> path.evidenceOccurrenceIds().stream()
                        .map(EvidenceOccurrenceId::value).reduce("", (left, right) -> left + "|" + right)));
        boolean pathCountTruncated = paths.size() > bounds.maximumPaths();
        if (pathCountTruncated) {
            paths.subList(bounds.maximumPaths(), paths.size()).clear();
        }
        return new TableImpactResult(
                snapshotId, snapshotCompletion, paths, depthTruncated || pathCountTruncated);
    }

    private static boolean collectPaths(
            NodeId current,
            CrudOperation operation,
            List<RelationshipAssertion> supported,
            Map<RelationshipAssertionId, List<EvidenceOccurrence>> evidence,
            List<NodeId> reversedNodes,
            List<EvidenceOccurrenceId> reversedEvidence,
            Set<NodeId> visited,
            QueryBounds bounds,
            List<ImpactPath> results) {
        reversedNodes.add(current);
        if (!visited.add(current)) {
            return false;
        }
        if (reversedEvidence.size() > bounds.maximumDepth()) {
            return true;
        }
        var incoming = supported.stream()
                .filter(assertion -> assertion.target().equals(current))
                .filter(assertion -> operationOf(assertion.type()).isEmpty())
                .toList();
        if (incoming.isEmpty()) {
            var nodes = new ArrayList<>(reversedNodes);
            java.util.Collections.reverse(nodes);
            var occurrences = new ArrayList<>(reversedEvidence);
            java.util.Collections.reverse(occurrences);
            results.add(new ImpactPath(operation, nodes, occurrences));
            return false;
        }

        boolean depthTruncated = false;
        for (var assertion : incoming) {
            if (results.size() >= bounds.maximumPaths()) {
                break;
            }
            for (var occurrence : evidence.get(assertion.id())) {
                var nextEvidence = new ArrayList<>(reversedEvidence);
                nextEvidence.add(occurrence.id());
                depthTruncated |= collectPaths(
                        assertion.source(), operation, supported, evidence,
                        new ArrayList<>(reversedNodes), nextEvidence, new HashSet<>(visited), bounds, results);
            }
        }
        return depthTruncated;
    }

    private static Optional<CrudOperation> operationOf(RelationshipType type) {
        return switch (type) {
            case READS -> Optional.of(CrudOperation.READS);
            case INSERTS -> Optional.of(CrudOperation.INSERTS);
            case UPDATES -> Optional.of(CrudOperation.UPDATES);
            case DELETES -> Optional.of(CrudOperation.DELETES);
            default -> Optional.empty();
        };
    }
}
