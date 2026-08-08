package io.graphcrud.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.graphcrud.application.PostgreSqlAnalysisInput;
import io.graphcrud.application.PostgreSqlAnalyzer;
import io.graphcrud.application.ProjectId;
import io.graphcrud.application.SnapshotCompletion;
import io.graphcrud.model.EvidenceLevel;
import io.graphcrud.model.EvidenceOccurrence;
import io.graphcrud.model.NodeFact;
import io.graphcrud.model.NodeId;
import io.graphcrud.model.NodeKind;
import io.graphcrud.model.RelationshipAssertion;
import io.graphcrud.model.RelationshipType;
import io.graphcrud.model.SnapshotId;
import io.graphcrud.model.SourceAnchor;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class JSqlParserPostgreSqlAnalyzerContractTest {
    private final PostgreSqlAnalyzer analyzer = new JSqlParserPostgreSqlAnalyzer();

    @Test
    void supported_sql_produces_source_backed_postgresql_crud_facts() {
        assertRoles("select * from Sales.Orders", Set.of("READS:orders"));
        assertRoles("insert into audit_log(id) select id from Sales.Orders", Set.of(
                "INSERTS:audit_log", "READS:orders"));
        assertRoles("update purchase_order set state='PAID' from payment where payment.id=purchase_order.id", Set.of(
                "UPDATES:purchase_order", "READS:payment"));
        assertRoles("delete from purchase_order using expired_order where purchase_order.id=expired_order.id", Set.of(
                "DELETES:purchase_order", "READS:expired_order"));
    }

    @Test
    void quoted_identifiers_preserve_case_and_unquoted_identifiers_fold_to_lowercase() {
        var result = analyze("select * from \"Sales\".\"OrderLine\"");
        var table = result.facts().stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .filter(node -> node.id().kind() == NodeKind.TABLE).findFirst().orElseThrow();

        assertEquals("Sales", table.id().identityParts().get("schema"));
        assertEquals("OrderLine", table.id().identityParts().get("name"));
        assertEquals("\"Sales\".\"OrderLine\"", table.properties().get("originalIdentifier"));
    }

    @Test
    void cte_and_merge_keep_distinct_read_and_write_roles() {
        assertRoles("with incoming as (select id from staging_order) insert into purchase_order(id) select id from incoming",
                Set.of("INSERTS:purchase_order", "READS:staging_order"));
        assertRoles("merge into purchase_order p using incoming_order i on p.id=i.id "
                        + "when matched then update set state=i.state "
                        + "when not matched then insert (id,state) values (i.id,i.state)",
                Set.of("INSERTS:purchase_order", "UPDATES:purchase_order", "READS:incoming_order"));
        assertRoles("merge into purchase_order p using incoming_order i on p.id=i.id "
                        + "when matched then update set state=i.state",
                Set.of("UPDATES:purchase_order", "READS:incoming_order"));
        assertRoles("merge into purchase_order p using incoming_order i on p.id=i.id "
                        + "when matched then delete",
                Set.of("DELETES:purchase_order", "READS:incoming_order"));
    }

    @Test
    void parsed_but_undeclared_table_remains_unresolved() {
        var sql = "select * from missing_table";
        var result = analyzer.analyze(new PostgreSqlAnalysisInput(
                new ProjectId("orders"), new SnapshotId("unknown"),
                NodeId.of(NodeKind.SQL_STATEMENT, Map.of("statement", "unknown")), sql,
                "orders-db", "public", Set.of(), new SourceAnchor("fixtures/unknown.sql", 1, 1)));

        assertEquals(SnapshotCompletion.PARTIAL, result.completion());
        assertTrue(result.facts().stream().filter(EvidenceOccurrence.class::isInstance)
                .map(EvidenceOccurrence.class::cast)
                .anyMatch(evidence -> evidence.evidenceLevel() == EvidenceLevel.UNRESOLVED
                        && evidence.explanation().startsWith("POSTGRESQL_TABLE_UNDECLARED:")));
    }

    @Test
    void parse_failure_retains_sql_evidence_and_never_guesses_crud() {
        var result = analyze("select from ??? runtime_table");

        assertEquals(SnapshotCompletion.PARTIAL, result.completion());
        assertTrue(result.facts().stream().noneMatch(RelationshipAssertion.class::isInstance));
        assertTrue(result.facts().stream().filter(EvidenceOccurrence.class::isInstance)
                .map(EvidenceOccurrence.class::cast)
                .anyMatch(evidence -> evidence.evidenceLevel() == EvidenceLevel.UNRESOLVED
                        && evidence.explanation().startsWith("POSTGRESQL_SQL_PARSE_FAILURE:")));
    }

    private void assertRoles(String sql, Set<String> expected) {
        var actual = analyze(sql).facts().stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .map(assertion -> assertion.type().name() + ":" + assertion.target().identityParts().get("name"))
                .collect(Collectors.toSet());
        assertEquals(expected, actual);
    }

    private io.graphcrud.application.PostgreSqlAnalysisResult analyze(String sql) {
        return analyzer.analyze(new PostgreSqlAnalysisInput(
                new ProjectId("orders"), new SnapshotId("stage-3"),
                NodeId.of(NodeKind.SQL_STATEMENT, Map.of("statement", Integer.toHexString(sql.hashCode()))),
                sql, "orders-db", "public", Set.of(
                        "sales.orders", "public.audit_log", "public.purchase_order", "public.payment",
                        "public.expired_order", "public.staging_order", "public.incoming_order",
                        "Sales.OrderLine"), new SourceAnchor("fixtures/stage-3.sql", 1, 1)));
    }
}
