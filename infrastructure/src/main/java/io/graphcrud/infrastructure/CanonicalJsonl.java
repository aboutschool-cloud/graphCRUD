package io.graphcrud.infrastructure;

import io.graphcrud.model.CanonicalFact;
import io.graphcrud.model.EvidenceOccurrence;
import io.graphcrud.model.NodeFact;
import io.graphcrud.model.RelationshipAssertion;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

final class CanonicalJsonl {
    private CanonicalJsonl() {}

    static byte[] write(Collection<? extends CanonicalFact> facts) {
        var jsonl = facts.stream()
                .sorted(Comparator.comparing(CanonicalFact::factKind)
                        .thenComparing(CanonicalFact::canonicalId))
                .map(CanonicalJsonl::toJson)
                .collect(Collectors.joining("\n", "", facts.isEmpty() ? "" : "\n"));
        return jsonl.getBytes(StandardCharsets.UTF_8);
    }

    private static String toJson(CanonicalFact fact) {
        if (fact instanceof NodeFact node) {
            return "{\"factKind\":\"node\",\"id\":" + quote(node.canonicalId())
                    + ",\"kind\":" + quote(node.id().kind().name())
                    + ",\"properties\":" + object(node.properties()) + "}";
        }
        if (fact instanceof RelationshipAssertion assertion) {
            return "{\"factKind\":\"relationshipAssertion\",\"id\":" + quote(assertion.canonicalId())
                    + ",\"qualifiers\":" + object(assertion.semanticQualifiers())
                    + ",\"source\":" + quote(assertion.source().canonicalValue())
                    + ",\"target\":" + quote(assertion.target().canonicalValue())
                    + ",\"type\":" + quote(assertion.type().name()) + "}";
        }
        if (fact instanceof EvidenceOccurrence occurrence) {
            var anchor = occurrence.sourceAnchor();
            return "{\"adapter\":" + quote(occurrence.adapter())
                    + ",\"evidenceLevel\":" + quote(occurrence.evidenceLevel().name())
                    + ",\"explanation\":" + quote(occurrence.explanation())
                    + ",\"factKind\":\"evidenceOccurrence\",\"id\":" + quote(occurrence.canonicalId())
                    + ",\"snapshotId\":" + quote(occurrence.snapshotId().value())
                    + ",\"sourceAnchor\":{\"column\":" + anchor.column()
                    + ",\"line\":" + anchor.line()
                    + ",\"path\":" + quote(anchor.path()) + "}"
                    + ",\"subjectId\":" + quote(occurrence.subject().subjectId())
                    + ",\"subjectKind\":" + quote(occurrence.subject().subjectKind()) + "}";
        }
        throw new IllegalArgumentException("unsupported canonical fact: " + fact.getClass().getName());
    }

    private static String object(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> quote(entry.getKey()) + ":" + quote(entry.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String quote(String value) {
        var escaped = new StringBuilder(value.length() + 2).append('"');
        value.codePoints().forEach(codePoint -> {
            switch (codePoint) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (codePoint < 0x20) {
                        escaped.append(String.format("\\u%04x", codePoint));
                    } else {
                        escaped.appendCodePoint(codePoint);
                    }
                }
            }
        });
        return escaped.append('"').toString();
    }
}
