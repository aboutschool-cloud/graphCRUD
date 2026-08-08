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

    @Test
    void mybatis_plus_base_mapper_calls_resolve_entity_table_and_mysql_ddl() throws Exception {
        write("src/main/java/com/acme/Order.java", """
                package com.acme;
                @TableName("t_order") public class Order {}
                """);
        write("src/main/java/com/acme/OrderMapper.java", """
                package com.acme;
                import com.baomidou.mybatisplus.core.mapper.BaseMapper;
                public interface OrderMapper extends BaseMapper<Order> {}
                class LaterType { void updateById(Order ignored) {} }
                """);
        write("src/main/java/com/acme/OrderService.java", """
                package com.acme;
                class OrderService {
                  private OrderMapper orderMapper;
                  void prior() { PositionMapper orderMapper = null; }
                  void work() {
                    { PositionMapper orderMapper = null; }
                    orderMapper.insert(new Order());
                    orderMapper.selectPage(page, wrapper);
                    orderMapper.updateById(new Order());
                  }
                  class Other { PositionMapper orderMapper; }
                }
                """);
        write("src/main/java/com/acme/Position.java", "package com.acme; @TableName(\"t_position\") class Position {}");
        write("src/main/java/com/acme/PositionMapper.java", """
                package com.acme;
                import com.baomidou.mybatisplus.core.mapper.BaseMapper;
                interface PositionMapper extends BaseMapper<Position> {}
                """);
        write("db/migration/V1__mysql.sql", "CREATE TABLE `t_order` (id BIGINT PRIMARY KEY) ENGINE=InnoDB;");
        var caller = NodeId.javaMethod("orders", "app", "java", "com.acme.OrderService", "work", "()V");
        var javaFacts = new java.util.ArrayList<io.graphcrud.model.CanonicalFact>();
        javaFacts.add(new NodeFact(caller, Map.of("displayName", "work")));
        javaFacts.add(unresolved(caller, 7, "orderMapper.insert(new Order())"));
        javaFacts.add(unresolved(caller, 8, "orderMapper.selectPage(page,wrapper)"));
        javaFacts.add(unresolved(caller, 9, "orderMapper.updateById(new Order())"));

        var result = analyze(Optional.of("mysql"), javaFacts);

        assertTrue(hasCrudFrom(result, caller, RelationshipType.INSERTS, "t_order", EvidenceLevel.CONFIRMED));
        assertTrue(hasCrudFrom(result, caller, RelationshipType.READS, "t_order", EvidenceLevel.CONFIRMED));
        assertTrue(hasCrudFrom(result, caller, RelationshipType.UPDATES, "t_order", EvidenceLevel.CONFIRMED));
        assertFalse(hasEvidence(result, "JAVA_DEPENDENCY_MISSING: raw=orderMapper.", EvidenceLevel.UNRESOLVED));
        assertEquals(4, result.facts().stream().filter(EvidenceOccurrence.class::isInstance)
                .map(EvidenceOccurrence.class::cast)
                .filter(e -> e.subject().subjectKind().equals("relationshipAssertion"))
                .filter(e -> e.explanation().contains("t_order") || e.explanation().contains("OrderMapper"))
                .filter(e -> e.sourceAnchor().path().contains("Order") || e.sourceAnchor().path().contains("mysql"))
                .count() / 3);
    }

    @Test
    void unrelated_base_mapper_and_custom_same_name_are_not_promoted() throws Exception {
        write("src/main/java/com/acme/Order.java", "package com.acme; @TableName(\"t_order\") class Order {}");
        write("src/main/java/com/acme/ForeignMapper.java", """
                package com.acme;
                interface BaseMapper<T> {}
                interface ForeignMapper extends BaseMapper<Order> {}
                """);
        write("src/main/java/com/acme/CustomMapper.java", """
                package com.acme;
                import com.baomidou.mybatisplus.core.mapper.BaseMapper;
                interface CustomMapper extends BaseMapper<Order> { int updateById(Order value); }
                """);
        write("src/main/java/com/acme/Service.java", """
                package com.acme; class Service { ForeignMapper foreignMapper; CustomMapper customMapper;
                void work() { foreignMapper.insert(new Order()); customMapper.updateById(new Order()); } }
                """);
        write("db/migration/V1.sql", "create table t_order(id bigint)");
        var caller = NodeId.javaMethod("orders", "app", "java", "com.acme.Service", "work", "()V");
        var facts = new java.util.ArrayList<io.graphcrud.model.CanonicalFact>();
        facts.add(new NodeFact(caller, Map.of()));
        facts.add(EvidenceOccurrence.of(caller, new SnapshotId("stage-3-project"), "eclipse-jdt",
                new io.graphcrud.model.SourceAnchor("src/main/java/com/acme/Service.java", 2, 31),
                EvidenceLevel.UNRESOLVED, "JAVA_DEPENDENCY_MISSING: raw=foreignMapper.insert(new Order()) candidates=[]"));
        facts.add(EvidenceOccurrence.of(caller, new SnapshotId("stage-3-project"), "eclipse-jdt",
                new io.graphcrud.model.SourceAnchor("src/main/java/com/acme/Service.java", 2, 70),
                EvidenceLevel.UNRESOLVED, "JAVA_DEPENDENCY_MISSING: raw=customMapper.updateById(new Order()) candidates=[]"));

        var result = analyze(Optional.of("mysql"), facts);
        assertFalse(hasCrudFrom(result, caller, RelationshipType.INSERTS, "t_order", EvidenceLevel.CONFIRMED));
        assertFalse(hasCrudFrom(result, caller, RelationshipType.UPDATES, "t_order", EvidenceLevel.CONFIRMED));
    }

    @Test
    void missing_ddl_is_possible_and_keeps_snapshot_partial() throws Exception {
        write("src/main/java/com/acme/Order.java", "package com.acme; @TableName(\"missing_order\") class Order {}");
        write("src/main/java/com/acme/OrderMapper.java", """
                package com.acme;
                import com.baomidou.mybatisplus.core.mapper.BaseMapper;
                interface OrderMapper extends BaseMapper<Order> {}
                """);
        write("src/main/java/com/acme/Service.java", "package com.acme; class Service { OrderMapper orderMapper; void work(){ orderMapper.insert(new Order()); }}");
        var caller = NodeId.javaMethod("orders", "app", "java", "com.acme.Service", "work", "()V");
        var facts = List.<io.graphcrud.model.CanonicalFact>of(new NodeFact(caller, Map.of()),
                EvidenceOccurrence.of(caller, new SnapshotId("stage-3-project"), "eclipse-jdt",
                        new io.graphcrud.model.SourceAnchor("src/main/java/com/acme/Service.java", 1, 75),
                        EvidenceLevel.UNRESOLVED, "JAVA_BINDING_NULL: raw=orderMapper.insert(new Order()) candidates=[]"));
        var result = analyze(Optional.of("mysql"), facts);
        assertTrue(hasCrudFrom(result, caller, RelationshipType.INSERTS, "missing_order", EvidenceLevel.POSSIBLE));
        assertEquals(SnapshotCompletion.PARTIAL, result.completion());
    }

    @Test
    void wrapper_logical_delete_and_optimistic_lock_are_qualified_without_double_counting() throws Exception {
        write("src/main/java/com/acme/Order.java", """
                package com.acme; @TableName("t_order") class Order {
                  @TableLogic int deleted; @Version int version;
                }
                """);
        write("src/main/java/com/acme/OrderMapper.java", """
                package com.acme;
                import com.baomidou.mybatisplus.core.mapper.BaseMapper;
                interface OrderMapper extends BaseMapper<Order> {}
                """);
        write("src/main/java/com/acme/Config.java", """
                package com.acme; class Config { void configure(MybatisPlusInterceptor value) {
                  value.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
                }}
                """);
        write("src/main/java/com/acme/Service.java", """
                package com.acme; class Service { OrderMapper orderMapper; void work(){
                  orderMapper.selectList(new LambdaQueryWrapper<Order>().eq(Order::id, 1).gt(Order::id, 0).orderByDesc(Order::id));
                  orderMapper.updateById(new Order());
                  orderMapper.deleteById(1L);
                }}
                """);
        write("db/migration/V1.sql", "create table t_order(id bigint)");
        var caller = NodeId.javaMethod("orders", "app", "java", "com.acme.Service", "work", "()V");
        var facts = new java.util.ArrayList<io.graphcrud.model.CanonicalFact>();
        facts.add(new NodeFact(caller, Map.of()));
        facts.add(unresolvedAt(caller, 2, "src/main/java/com/acme/Service.java",
                "orderMapper.selectList(new LambdaQueryWrapper<Order>().eq(Order::id,1).gt(Order::id,0).orderByDesc(Order::id))"));
        facts.add(unresolvedAt(caller, 3, "src/main/java/com/acme/Service.java", "orderMapper.updateById(new Order())"));
        facts.add(unresolvedAt(caller, 4, "src/main/java/com/acme/Service.java", "orderMapper.deleteById(1L)"));
        var result = analyze(Optional.of("mysql"), facts);
        var relationships = result.facts().stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast).filter(a -> a.source().equals(caller)).toList();
        assertTrue(relationships.stream().anyMatch(a -> a.type() == RelationshipType.READS
                && "PARTIAL".equals(a.semanticQualifiers().get("wrapperCoverage"))
                && a.semanticQualifiers().get("wrapperOperations").contains("orderByDesc")));
        assertTrue(relationships.stream().anyMatch(a -> a.type() == RelationshipType.UPDATES
                && "CONFIGURED".equals(a.semanticQualifiers().get("optimisticLock"))));
        assertTrue(relationships.stream().anyMatch(a -> a.type() == RelationshipType.DELETES
                && "UPDATE_LOGICAL_DELETE".equals(a.semanticQualifiers().get("physicalEffect"))));
    }

    @Test
    void intermediate_generic_mapper_substitutes_entity_type() throws Exception {
        write("src/main/java/com/acme/Order.java", "package com.acme; @TableName(\"t_order\") class Order {}");
        write("src/main/java/com/acme/RootMapper.java", """
                package com.acme;
                import com.baomidou.mybatisplus.core.mapper.BaseMapper;
                interface RootMapper<T> extends BaseMapper<T> {}
                """);
        write("src/main/java/com/acme/OrderMapper.java",
                "package com.acme; interface OrderMapper extends RootMapper<Order> {}");
        write("src/main/java/com/acme/Service.java",
                "package com.acme; class Service { OrderMapper orderMapper; void work(){ orderMapper.insert(new Order()); }}");
        write("db/migration/V1.sql", "create table t_order(id bigint)");
        var caller = NodeId.javaMethod("orders", "app", "java", "com.acme.Service", "work", "()V");
        var facts = List.<io.graphcrud.model.CanonicalFact>of(new NodeFact(caller, Map.of()),
                EvidenceOccurrence.of(caller, new SnapshotId("stage-3-project"), "eclipse-jdt",
                        new io.graphcrud.model.SourceAnchor("src/main/java/com/acme/Service.java", 1, 75),
                        EvidenceLevel.UNRESOLVED,
                        "JAVA_DEPENDENCY_MISSING: raw=orderMapper.insert(new Order()) candidates=[]"));
        var result = analyze(Optional.of("mysql"), facts);
        assertTrue(hasCrudFrom(result, caller, RelationshipType.INSERTS, "t_order", EvidenceLevel.CONFIRMED));
    }

    @Test
    void commented_optimistic_locker_configuration_is_not_confirmed() throws Exception {
        write("src/main/java/com/acme/Order.java", "package com.acme; @TableName(\"t_order\") class Order { @Version int version; }");
        write("src/main/java/com/acme/OrderMapper.java", """
                package com.acme;
                import com.baomidou.mybatisplus.core.mapper.BaseMapper;
                interface OrderMapper extends BaseMapper<Order> {}
                """);
        write("src/main/java/com/acme/Config.java", "package com.acme; class Config { // value.addInnerInterceptor(new OptimisticLockerInnerInterceptor());\n }");
        write("src/main/java/com/acme/Service.java", "package com.acme; class Service { OrderMapper orderMapper; void work(){ orderMapper.updateById(new Order()); }}");
        write("db/migration/V1.sql", "create table t_order(id bigint)");
        var caller = NodeId.javaMethod("orders", "app", "java", "com.acme.Service", "work", "()V");
        var facts = List.<io.graphcrud.model.CanonicalFact>of(new NodeFact(caller, Map.of()),
                EvidenceOccurrence.of(caller, new SnapshotId("stage-3-project"), "eclipse-jdt",
                        new io.graphcrud.model.SourceAnchor("src/main/java/com/acme/Service.java", 1, 75),
                        EvidenceLevel.UNRESOLVED,
                        "JAVA_DEPENDENCY_MISSING: raw=orderMapper.updateById(new Order()) candidates=[]"));
        var result = analyze(Optional.of("mysql"), facts);
        assertTrue(result.facts().stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast).filter(a -> a.source().equals(caller))
                .noneMatch(a -> "CONFIGURED".equals(a.semanticQualifiers().get("optimisticLock"))));
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

    private static boolean hasCrudFrom(io.graphcrud.application.JavaAnalysisResult result, NodeId source,
            RelationshipType type, String table, EvidenceLevel level) {
        return result.facts().stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .filter(a -> a.source().equals(source) && a.type() == type
                        && table.equals(a.target().identityParts().get("name")))
                .anyMatch(a -> result.facts().stream().filter(EvidenceOccurrence.class::isInstance)
                        .map(EvidenceOccurrence.class::cast)
                        .anyMatch(e -> e.subject().equals(a.id()) && e.evidenceLevel() == level));
    }

    private static EvidenceOccurrence unresolved(NodeId caller, int line, String raw) {
        return unresolvedAt(caller, line, "src/main/java/com/acme/OrderService.java", raw);
    }

    private static EvidenceOccurrence unresolvedAt(NodeId caller, int line, String path, String raw) {
        return EvidenceOccurrence.of(caller, new SnapshotId("stage-3-project"), "eclipse-jdt",
                new io.graphcrud.model.SourceAnchor(path, line, 5),
                EvidenceLevel.UNRESOLVED, "JAVA_DEPENDENCY_MISSING: raw=" + raw + " candidates=[]");
    }

    private void write(String relative, String content) throws Exception {
        var path = root.resolve(relative); Files.createDirectories(path.getParent()); Files.writeString(path, content);
    }
}
