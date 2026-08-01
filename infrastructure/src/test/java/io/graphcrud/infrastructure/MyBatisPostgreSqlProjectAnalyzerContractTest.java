package io.graphcrud.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.graphcrud.application.PersistenceProjectAnalysisInput;
import io.graphcrud.application.PersistenceProjectAnalyzer;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MyBatisPostgreSqlProjectAnalyzerContractTest {
    @TempDir Path root;
    private final PersistenceProjectAnalyzer analyzer = new MyBatisPostgreSqlProjectAnalyzer();

    @Test
    void unique_xml_database_variant_reaches_table_while_dynamic_branch_is_possible() throws Exception {
        write("db/migration/V1__tables.sql", "create table purchase_order(id bigint)");
        write("mapper/OrderMapper.xml", """
                <mapper namespace="com.acme.OrderMapper">
                  <select id="find" databaseId="postgresql">select * from purchase_order</select>
                  <select id="find" databaseId="oracle">select * from PURCHASE_ORDER</select>
                  <select id="search">select * from purchase_order <if test="state != null">where state=#{state}</if></select>
                  <select id="choice">select * from purchase_order where state = <choose><when test="a">'A'</when><otherwise>'B'</otherwise></choose></select>
                  <select id="many">select * from purchase_order where id in <foreach collection="ids">#{id}</foreach></select>
                </mapper>
                """);
        var result = analyze(Optional.of("postgresql"), List.of(
                method("find", "()Ljava/lang/Object;"), method("search", "()Ljava/lang/Object;"),
                method("choice", "()Ljava/lang/Object;"), method("many", "()Ljava/lang/Object;")));

        assertTrue(hasCrud(result, RelationshipType.READS, "purchase_order", EvidenceLevel.CONFIRMED));
        assertTrue(hasEvidence(result, "MYBATIS_DYNAMIC_SQL_BRANCH", EvidenceLevel.POSSIBLE));
        assertTrue(hasEvidence(result, "MYBATIS_DYNAMIC_SQL_UNBOUNDED:", EvidenceLevel.UNRESOLVED));
        assertTrue(result.facts().stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .filter(node -> "choice".equals(node.properties().get("statementId")))
                .noneMatch(node -> node.properties().get("sql").contains("'A'")
                        && node.properties().get("sql").contains("'B'")));
    }

    @Test
    void overload_and_runtime_database_selection_are_never_confirmed_unique() throws Exception {
        write("mapper/OrderMapper.xml", """
                <mapper namespace="com.acme.OrderMapper">
                  <select id="find" databaseId="postgresql">select * from purchase_order</select>
                  <select id="find" databaseId="oracle">select * from PURCHASE_ORDER</select>
                </mapper>
                """);
        var overloaded = analyze(Optional.of("postgresql"), List.of(method("find", "()V"), method("find", "(I)V")));
        assertTrue(hasEvidence(overloaded, "MYBATIS_XML_METHOD_AMBIGUOUS:", EvidenceLevel.POSSIBLE));
        assertFalse(overloaded.facts().stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast).anyMatch(a -> a.type() == RelationshipType.EXECUTES));

        var runtime = analyze(Optional.empty(), List.of(method("find", "()V")));
        assertTrue(hasEvidence(runtime, "MYBATIS_DATABASE_ID_RUNTIME_DEPENDENT:", EvidenceLevel.UNRESOLVED));
    }

    @Test
    void schema_views_routines_static_execute_and_trigger_chains_are_source_backed() throws Exception {
        write("mapper/OrderMapper.xml", """
                <mapper namespace="com.acme.OrderMapper">
                  <select id="active">select * from active_orders</select>
                  <update id="updateActive">update active_orders set active=false</update>
                  <insert id="create">insert into purchase_order(id) values (1)</insert>
                  <select id="refresh">call refresh_orders()</select>
                  <update id="updateComplex">update complex_orders set active=false</update>
                  <select id="conflict">select * from conflict_view</select>
                  <update id="updateLimited">update limited_orders set active=false</update>
                </mapper>
                """);
        write("db/migration/V1__tables.sql", "create table purchase_order(id bigint); create table audit_log(id bigint);");
        write("db/migration/V2__view.sql", "create view active_orders as select * from purchase_order where active=true");
        write("db/migration/V3__routine.sql", """
                create function audit_order() returns trigger language plpgsql as $$
                begin
                  insert into audit_log(id) values (1);
                  execute 'update purchase_order set audited=true';
                  execute runtime_sql;
                  return new;
                end
                $$
                ;
                create procedure refresh_orders() language plpgsql as $$
                begin
                  update purchase_order set refreshed=true;
                end
                $$
                """);
        write("db/migration/V4__trigger.sql", """
                create trigger purchase_order_audit after insert on purchase_order
                for each row execute function audit_order()
                """);
        write("db/migration/V5__conflict_a.sql", "create view conflict_view as select * from purchase_order");
        write("db/migration/V5_1__conflict_b.sql", "create view conflict_view as select * from audit_log");
        write("db/migration/V6__complex_view.sql", "create view complex_orders as select p.id from purchase_order p join audit_log a on a.id=p.id");
        write("db/migration/V7__limited_view.sql", "create view limited_orders as select id from purchase_order limit 1");
        var result = analyze(Optional.of("postgresql"), List.of(
                method("active", "()Ljava/lang/Object;"), method("updateActive", "()V"),
                method("create", "()V"), method("refresh", "()V"), method("updateComplex", "()V"),
                method("conflict", "()V"), method("updateLimited", "()V")));

        assertTrue(hasKind(result, NodeKind.VIEW));
        assertTrue(hasKind(result, NodeKind.DATABASE_ROUTINE));
        assertTrue(hasKind(result, NodeKind.TRIGGER));
        assertTrue(hasCrud(result, RelationshipType.READS, "purchase_order", EvidenceLevel.CONFIRMED));
        assertTrue(hasCrud(result, RelationshipType.INSERTS, "audit_log", EvidenceLevel.CONFIRMED));
        assertTrue(hasCrud(result, RelationshipType.UPDATES, "purchase_order", EvidenceLevel.CONFIRMED));
        assertTrue(result.facts().stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast).anyMatch(a -> a.type() == RelationshipType.TRIGGERS));
        assertTrue(result.facts().stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .anyMatch(a -> a.type() == RelationshipType.READS && a.target().kind() == NodeKind.VIEW));
        assertTrue(hasEvidence(result, "POSTGRESQL_DYNAMIC_EXECUTE:", EvidenceLevel.UNRESOLVED));
        assertTrue(hasEvidence(result, "POSTGRESQL_SCHEMA_CONFLICT_VIEW:", EvidenceLevel.UNRESOLVED));
        assertTrue(result.facts().stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .anyMatch(a -> a.type() == RelationshipType.EXECUTES
                        && a.target().kind() == NodeKind.DATABASE_ROUTINE));
        assertTrue(result.facts().stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .anyMatch(a -> a.type() == RelationshipType.TRIGGERS
                        && "INSERT".equals(a.semanticQualifiers().get("event"))));
        assertTrue(result.facts().stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .anyMatch(a -> a.type() == RelationshipType.UPDATES
                        && a.source().kind() == NodeKind.VIEW
                        && "purchase_order".equals(a.target().identityParts().get("name"))));
        assertTrue(hasEvidence(result, "POSTGRESQL_VIEW_WRITE_UNSUPPORTED:", EvidenceLevel.UNRESOLVED));
        assertTrue(hasCrud(result, RelationshipType.READS, "conflict_view", EvidenceLevel.UNRESOLVED));
        assertEquals(SnapshotCompletion.PARTIAL, result.completion());
    }

    @Test
    void explicit_environment_and_priority_choose_the_winning_schema_source_and_report_conflict() throws Exception {
        write("schema/low/V1__view.sql", "create table low_table(id bigint); create view priority_view as select * from low_table");
        write("schema/high/V1__view.sql", "create table high_table(id bigint); create view priority_view as select * from high_table");
        write("schema/prod/V1__view.sql", "create table prod_table(id bigint); create view prod_only as select * from prod_table");
        var sources = List.of(
                new io.graphcrud.application.SchemaSource(root.resolve("schema/low"), "test", 10),
                new io.graphcrud.application.SchemaSource(root.resolve("schema/high"), "test", 100),
                new io.graphcrud.application.SchemaSource(root.resolve("schema/prod"), "prod", 1000));
        var result = analyzer.analyze(new PersistenceProjectAnalysisInput(
                new ProjectId("orders"), new SnapshotId("priority"), root, "app", "orders-db", "public",
                Optional.of("postgresql"), "test", sources, List.of()));

        assertTrue(hasCrud(result, RelationshipType.READS, "high_table", EvidenceLevel.UNRESOLVED));
        assertFalse(hasCrud(result, RelationshipType.READS, "low_table", EvidenceLevel.CONFIRMED));
        assertFalse(hasKindNamed(result, NodeKind.VIEW, "prod_only"));
        assertTrue(hasEvidence(result, "POSTGRESQL_SCHEMA_CONFLICT_VIEW:", EvidenceLevel.UNRESOLVED));
    }

    @Test
    void overloaded_routine_call_is_unresolved_without_a_unique_static_argument_type() throws Exception {
        write("mapper/RoutineMapper.xml", """
                <mapper namespace="com.acme.OrderMapper"><select id="run">call recalc(runtime_value)</select></mapper>
                """);
        write("db/migration/V1__routines.sql", """
                create procedure recalc(value integer) language plpgsql as $$ begin select 1; end $$;
                create procedure recalc(value text) language plpgsql as $$ begin select 1; end $$
                """);
        var result = analyze(Optional.of("postgresql"), List.of(method("run", "()V")));

        assertTrue(hasEvidence(result, "POSTGRESQL_ROUTINE_MISSING_OR_AMBIGUOUS:", EvidenceLevel.UNRESOLVED));
        assertFalse(result.facts().stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .anyMatch(a -> a.type() == RelationshipType.EXECUTES
                        && a.target().kind() == NodeKind.DATABASE_ROUTINE));
    }

    private io.graphcrud.application.JavaAnalysisResult analyze(Optional<String> databaseId, List<io.graphcrud.model.CanonicalFact> facts) {
        var migrationRoot = root.resolve("db/migration");
        var schemaSources = Files.exists(migrationRoot)
                ? List.of(new io.graphcrud.application.SchemaSource(migrationRoot, "test", 100))
                : List.<io.graphcrud.application.SchemaSource>of();
        return analyzer.analyze(new PersistenceProjectAnalysisInput(
                new ProjectId("orders"), new SnapshotId("stage-3-project"), root, "app", "orders-db", "public",
                databaseId, "test", schemaSources, facts));
    }

    private static NodeFact method(String name, String signature) {
        var id = NodeId.javaMethod("orders", "app", "java", "com.acme.OrderMapper", name, signature);
        return new NodeFact(id, Map.of("displayName", name));
    }

    private static boolean hasKind(io.graphcrud.application.JavaAnalysisResult result, NodeKind kind) {
        return result.facts().stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .anyMatch(node -> node.id().kind() == kind);
    }

    private static boolean hasKindNamed(io.graphcrud.application.JavaAnalysisResult result, NodeKind kind, String name) {
        return result.facts().stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .anyMatch(node -> node.id().kind() == kind && name.equals(node.id().identityParts().get("name")));
    }

    private static boolean hasEvidence(io.graphcrud.application.JavaAnalysisResult result, String prefix, EvidenceLevel level) {
        return result.facts().stream().filter(EvidenceOccurrence.class::isInstance).map(EvidenceOccurrence.class::cast)
                .anyMatch(e -> e.evidenceLevel() == level && e.explanation().startsWith(prefix));
    }

    private static boolean hasCrud(io.graphcrud.application.JavaAnalysisResult result, RelationshipType type, String table, EvidenceLevel level) {
        return result.facts().stream().filter(RelationshipAssertion.class::isInstance).map(RelationshipAssertion.class::cast)
                .filter(a -> a.type() == type && table.equals(a.target().identityParts().get("name")))
                .anyMatch(a -> result.facts().stream().filter(EvidenceOccurrence.class::isInstance)
                        .map(EvidenceOccurrence.class::cast)
                        .anyMatch(e -> e.subject().equals(a.id()) && e.evidenceLevel() == level));
    }

    private void write(String relative, String content) throws Exception {
        var path = root.resolve(relative); Files.createDirectories(path.getParent()); Files.writeString(path, content);
    }
}
