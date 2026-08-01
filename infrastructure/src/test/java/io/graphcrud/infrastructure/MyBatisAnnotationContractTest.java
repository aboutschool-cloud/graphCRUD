package io.graphcrud.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.graphcrud.application.JavaAnalysisInput;
import io.graphcrud.application.JavaBuildMetadata;
import io.graphcrud.application.JavaSourceAnalyzer;
import io.graphcrud.application.ProjectId;
import io.graphcrud.application.SnapshotCompletion;
import io.graphcrud.model.EvidenceLevel;
import io.graphcrud.model.EvidenceOccurrence;
import io.graphcrud.model.NodeKind;
import io.graphcrud.model.RelationshipAssertion;
import io.graphcrud.model.RelationshipType;
import io.graphcrud.model.SnapshotId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MyBatisAnnotationContractTest {
    @TempDir Path projectRoot;

    @Test
    void crud_annotations_bind_to_mapper_methods_and_provider_sql_stays_unresolved() throws Exception {
        var root = projectRoot.resolve("src/main/java");
        annotation(root, "Select"); annotation(root, "Insert"); annotation(root, "Update"); annotation(root, "Delete");
        write(root.resolve("org/apache/ibatis/annotations/SelectProvider.java"),
                "package org.apache.ibatis.annotations; public @interface SelectProvider { Class<?> type(); String method(); }");
        write(root.resolve("com/acme/OrderMapper.java"), """
                package com.acme;
                import org.apache.ibatis.annotations.*;
                public interface OrderMapper {
                    @Select("select * from purchase_order") Object find();
                    @Insert("insert into audit_log(id) values (1)") void insert();
                    @Update("update purchase_order set state='PAID'") void update();
                    @Delete("delete from expired_order") void delete();
                    @SelectProvider(type=Provider.class, method="sql") Object generated();
                }
                class Provider { static String sql() { return "select 1"; } }
                """);
        JavaSourceAnalyzer analyzer = new EclipseJdtJavaSourceAnalyzer();
        var result = analyzer.analyze(new JavaAnalysisInput(
                new ProjectId("orders"), new SnapshotId("mybatis-annotations"), projectRoot,
                new JavaBuildMetadata("app", Map.of(root, StandardCharsets.UTF_8), List.of(), true,
                        Optional.of(new io.graphcrud.application.PostgreSqlContext(
                                "orders-db", "public", java.util.Set.of(
                                        "public.purchase_order", "public.audit_log", "public.expired_order"))))));

        var crud = result.facts().stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .filter(assertion -> assertion.target().kind() == NodeKind.TABLE)
                .map(RelationshipAssertion::type).toList();
        assertTrue(crud.containsAll(List.of(
                RelationshipType.READS, RelationshipType.INSERTS,
                RelationshipType.UPDATES, RelationshipType.DELETES)));
        assertEquals(SnapshotCompletion.PARTIAL, result.completion());
        assertTrue(result.facts().stream().filter(EvidenceOccurrence.class::isInstance)
                .map(EvidenceOccurrence.class::cast)
                .anyMatch(e -> e.evidenceLevel() == EvidenceLevel.UNRESOLVED
                        && e.explanation().startsWith("MYBATIS_PROVIDER_RUNTIME_DEPENDENT:")));
    }

    @Test
    void xml_and_annotation_conflict_downgrades_both_sources_from_confirmed() throws Exception {
        var root = projectRoot.resolve("src/main/java");
        annotation(root, "Select");
        write(root.resolve("com/acme/OrderMapper.java"), """
                package com.acme;
                import org.apache.ibatis.annotations.Select;
                public interface OrderMapper {
                    @Select("select * from annotated_order") Object find();
                }
                """);
        write(projectRoot.resolve("src/main/resources/OrderMapper.xml"), """
                <mapper namespace="com.acme.OrderMapper">
                  <select id="find">select * from xml_order</select>
                </mapper>
                """);
        var javaResult = new EclipseJdtJavaSourceAnalyzer().analyze(new JavaAnalysisInput(
                new ProjectId("orders"), new SnapshotId("mybatis-conflict"), projectRoot,
                new JavaBuildMetadata("app", Map.of(root, StandardCharsets.UTF_8), List.of(), true,
                        Optional.of(new io.graphcrud.application.PostgreSqlContext(
                                "orders-db", "public", java.util.Set.of(
                                        "public.annotated_order", "public.xml_order"))))));
        var result = new MyBatisPostgreSqlProjectAnalyzer().analyze(
                new io.graphcrud.application.PersistenceProjectAnalysisInput(
                        new ProjectId("orders"), new SnapshotId("mybatis-conflict"), projectRoot,
                        "app", "orders-db", "public", Optional.of("postgresql"), "test", List.of(), javaResult.facts()));

        assertTrue(result.facts().stream().filter(EvidenceOccurrence.class::isInstance)
                .map(EvidenceOccurrence.class::cast)
                .anyMatch(e -> e.evidenceLevel() == EvidenceLevel.POSSIBLE
                        && e.explanation().startsWith("MYBATIS_XML_ANNOTATION_CONFLICT:")));
        assertTrue(result.facts().stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .filter(a -> a.target().kind() == NodeKind.TABLE)
                .noneMatch(a -> result.facts().stream().filter(EvidenceOccurrence.class::isInstance)
                        .map(EvidenceOccurrence.class::cast)
                        .anyMatch(e -> e.subject().equals(a.id()) && e.evidenceLevel() == EvidenceLevel.CONFIRMED)));
    }

    private static void annotation(Path root, String name) throws Exception {
        write(root.resolve("org/apache/ibatis/annotations/" + name + ".java"),
                "package org.apache.ibatis.annotations; public @interface " + name + " { String[] value(); }");
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
