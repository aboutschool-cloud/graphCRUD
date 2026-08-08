package io.graphcrud.infrastructure;

import io.graphcrud.model.CanonicalFact;
import io.graphcrud.model.EvidenceLevel;
import io.graphcrud.model.EvidenceOccurrence;
import io.graphcrud.model.EvidenceSubject;
import io.graphcrud.model.NodeFact;
import io.graphcrud.model.NodeId;
import io.graphcrud.model.NodeKind;
import io.graphcrud.model.RelationshipAssertion;
import io.graphcrud.model.RelationshipAssertionId;
import io.graphcrud.model.RelationshipType;
import io.graphcrud.model.SnapshotId;
import io.graphcrud.model.SourceAnchor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

final class CanonicalFactCodec {
    private CanonicalFactCodec() {}

    static String encode(CanonicalFact fact) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                if (fact instanceof NodeFact node) {
                    output.writeByte(1); writeNode(output, node.id()); writeMap(output, node.properties());
                } else if (fact instanceof RelationshipAssertion relationship) {
                    output.writeByte(2); writeNode(output, relationship.source());
                    output.writeUTF(relationship.type().name()); writeNode(output, relationship.target());
                    writeMap(output, relationship.semanticQualifiers());
                } else if (fact instanceof EvidenceOccurrence evidence) {
                    output.writeByte(3); output.writeUTF(evidence.subject().subjectKind());
                    output.writeUTF(evidence.subject().subjectId()); output.writeUTF(evidence.snapshotId().value());
                    output.writeUTF(evidence.adapter()); output.writeUTF(evidence.sourceAnchor().path());
                    output.writeInt(evidence.sourceAnchor().line()); output.writeInt(evidence.sourceAnchor().column());
                    output.writeUTF(evidence.evidenceLevel().name()); output.writeUTF(evidence.explanation());
                } else throw new IllegalArgumentException("unsupported fact type: " + fact.getClass().getName());
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static CanonicalFact decode(String encoded) {
        try (var input = new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))) {
            return switch (input.readByte()) {
                case 1 -> new NodeFact(readNode(input), readMap(input));
                case 2 -> new RelationshipAssertion(readNode(input), RelationshipType.valueOf(input.readUTF()),
                        readNode(input), readMap(input));
                case 3 -> {
                    String subjectKind = input.readUTF();
                    String subjectId = input.readUTF();
                    EvidenceSubject subject = subjectKind.equals("node") ? parseNode(subjectId)
                            : new RelationshipAssertionId(subjectId);
                    yield EvidenceOccurrence.of(subject, new SnapshotId(input.readUTF()), input.readUTF(),
                            new SourceAnchor(input.readUTF(), input.readInt(), input.readInt()),
                            EvidenceLevel.valueOf(input.readUTF()), input.readUTF());
                }
                default -> throw new IllegalArgumentException("unsupported encoded fact");
            };
        } catch (IOException failure) {
            throw new IllegalArgumentException("invalid encoded canonical fact", failure);
        }
    }

    private static void writeNode(DataOutputStream output, NodeId node) throws IOException {
        output.writeUTF(node.kind().name()); writeMap(output, node.identityParts());
    }

    private static NodeId readNode(DataInputStream input) throws IOException {
        return NodeId.of(NodeKind.valueOf(input.readUTF()), readMap(input));
    }

    private static NodeId parseNode(String canonical) {
        int separator = canonical.indexOf(':');
        var kind = NodeKind.valueOf(canonical.substring(0, separator));
        var parts = new TreeMap<String, String>();
        for (var entry : canonical.substring(separator + 1).split("&")) {
            int equals = entry.indexOf('=');
            parts.put(decodePart(entry.substring(0, equals)), decodePart(entry.substring(equals + 1)));
        }
        return NodeId.of(kind, parts);
    }

    private static String decodePart(String value) {
        return new String(Base64.getUrlDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void writeMap(DataOutputStream output, Map<String, String> values) throws IOException {
        output.writeInt(values.size());
        for (var entry : new TreeMap<>(values).entrySet()) { output.writeUTF(entry.getKey()); output.writeUTF(entry.getValue()); }
    }

    private static Map<String, String> readMap(DataInputStream input) throws IOException {
        var values = new TreeMap<String, String>();
        for (int index = 0, size = input.readInt(); index < size; index++) values.put(input.readUTF(), input.readUTF());
        return values;
    }
}
