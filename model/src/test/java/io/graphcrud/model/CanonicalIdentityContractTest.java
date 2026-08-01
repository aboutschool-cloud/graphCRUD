package io.graphcrud.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalIdentityContractTest {
    @Test
    void logical_identity_is_stable_and_distinguishes_different_nodes() {
        var firstOrder = new LinkedHashMap<String, String>();
        firstOrder.put("project", "billing");
        firstOrder.put("module", "invoices");
        firstOrder.put("language", "java");
        firstOrder.put("declaringType", "com.acme.InvoiceService");
        firstOrder.put("methodName", "find");
        firstOrder.put("jvmParameterSignature", "(Ljava/lang/String;)LInvoice;");

        var reverseOrder = new LinkedHashMap<String, String>();
        reverseOrder.put("jvmParameterSignature", "(Ljava/lang/String;)LInvoice;");
        reverseOrder.put("methodName", "find");
        reverseOrder.put("declaringType", "com.acme.InvoiceService");
        reverseOrder.put("language", "java");
        reverseOrder.put("module", "invoices");
        reverseOrder.put("project", "billing");

        var first = NodeId.of(NodeKind.CODE_SYMBOL, firstOrder);
        var sameLogicalNode = NodeId.of(NodeKind.CODE_SYMBOL, reverseOrder);
        var otherMethod = NodeId.javaMethod(
                "billing", "invoices", "java", "com.acme.InvoiceService", "save", "(LInvoice;)V");

        assertEquals(first, sameLogicalNode);
        assertEquals(first.canonicalValue(), sameLogicalNode.canonicalValue());
        assertNotEquals(first, otherMethod);
    }

    @Test
    void snapshot_and_evidence_metadata_cannot_enter_logical_identity() {
        for (var forbiddenKey : List.of(
                "snapshotId", "sourceLocation", "evidenceLevel", "commit", "displayName")) {
            assertThrows(IllegalArgumentException.class, () -> NodeId.of(
                    NodeKind.CODE_SYMBOL,
                    Map.of("symbol", "Orders#submit()V", forbiddenKey, "metadata")));
        }
    }

    @Test
    void java_method_identity_requires_every_normative_key_part() {
        var method = NodeId.javaMethod(
                "billing", "orders", "java", "com.acme.Orders", "submit", "()V");

        assertEquals(Map.of(
                "project", "billing",
                "module", "orders",
                "language", "java",
                "declaringType", "com.acme.Orders",
                "methodName", "submit",
                "jvmParameterSignature", "()V"), method.identityParts());
        assertThrows(IllegalArgumentException.class, () ->
                NodeId.of(NodeKind.CODE_SYMBOL, Map.of("symbol", "Orders#submit()V")));
    }

    @Test
    void relationship_identity_distinguishes_qualifier_delimiters_from_qualifier_structure() {
        var source = NodeId.of(NodeKind.SQL_STATEMENT, Map.of("statement", "select-orders"));
        var target = NodeId.of(NodeKind.TABLE, Map.of("table", "orders"));
        var embeddedDelimiters = RelationshipAssertion.of(
                source, RelationshipType.READS, target, Map.of("a", "b&c=d"));
        var separateQualifiers = RelationshipAssertion.of(
                source, RelationshipType.READS, target, Map.of("a", "b", "c", "d"));

        assertNotEquals(embeddedDelimiters.id(), separateQualifiers.id());
    }
}
