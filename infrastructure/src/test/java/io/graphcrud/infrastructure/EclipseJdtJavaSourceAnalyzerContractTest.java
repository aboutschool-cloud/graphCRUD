package io.graphcrud.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.graphcrud.application.JavaAnalysisInput;
import io.graphcrud.application.JavaBuildMetadata;
import io.graphcrud.application.JavaSourceAnalyzer;
import io.graphcrud.application.ProjectId;
import io.graphcrud.model.EvidenceOccurrence;
import io.graphcrud.model.NodeFact;
import io.graphcrud.model.NodeId;
import io.graphcrud.model.NodeKind;
import io.graphcrud.model.RelationshipAssertion;
import io.graphcrud.model.RelationshipType;
import io.graphcrud.model.SnapshotId;
import io.graphcrud.application.SnapshotCompletion;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EclipseJdtJavaSourceAnalyzerContractTest {
    @TempDir
    Path projectRoot;

    @Test
    void minimal_java_source_produces_deterministic_source_and_method_facts() throws Exception {
        var sourceRoot = projectRoot.resolve("src/main/java");
        var sourceFile = sourceRoot.resolve("com/acme/Orders.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package com.acme;

                public class Orders {
                    public void submit(String orderId) {}
                }
                """, StandardCharsets.UTF_8);

        JavaSourceAnalyzer analyzer = new EclipseJdtJavaSourceAnalyzer();
        var input = new JavaAnalysisInput(
                new ProjectId("orders"),
                new SnapshotId("snapshot-2"),
                projectRoot,
                new JavaBuildMetadata(
                        "app", Map.of(sourceRoot, StandardCharsets.UTF_8), List.of(), true));

        var first = analyzer.analyze(input).facts();
        var second = analyzer.analyze(input).facts();
        var sourceId = NodeId.of(NodeKind.SOURCE_FILE, Map.of(
                "module", "app", "path", "src/main/java/com/acme/Orders.java", "project", "orders"));
        var methodId = NodeId.javaMethod(
                "orders", "app", "java", "com.acme.Orders", "submit", "(Ljava/lang/String;)V");

        assertEquals(first, second);
        assertTrue(first.stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .anyMatch(fact -> fact.id().equals(sourceId)));
        assertTrue(first.stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .anyMatch(fact -> fact.id().equals(methodId)));
        assertEquals(2, first.stream().filter(EvidenceOccurrence.class::isInstance).count());
    }

    @Test
    void symbolic_link_escape_is_not_analyzed(@TempDir Path outsideRoot) throws Exception {
        var sourceRoot = projectRoot.resolve("src/main/java");
        Files.createDirectories(sourceRoot);
        var outsideFile = outsideRoot.resolve("Escaped.java");
        Files.writeString(outsideFile, "public class Escaped {}", StandardCharsets.UTF_8);
        Files.createSymbolicLink(sourceRoot.resolve("Escaped.java"), outsideFile);
        JavaSourceAnalyzer analyzer = new EclipseJdtJavaSourceAnalyzer();
        var input = new JavaAnalysisInput(
                new ProjectId("orders"),
                new SnapshotId("snapshot-escape"),
                projectRoot,
                new JavaBuildMetadata(
                        "app", Map.of(sourceRoot, StandardCharsets.UTF_8), List.of(), true));

        assertTrue(analyzer.analyze(input).facts().isEmpty());
    }

    @Test
    void built_in_and_project_ignore_rules_filter_discovered_sources() throws Exception {
        var sourceRoot = projectRoot.resolve("src/main/java");
        writeJava(sourceRoot.resolve("com/acme/Included.java"), "package com.acme; public class Included {}");
        writeJava(sourceRoot.resolve("build/Generated.java"), "public class Generated {}");
        writeJava(sourceRoot.resolve("com/acme/Ignored.java"), "package com.acme; public class Ignored {}");
        Files.writeString(
                projectRoot.resolve(".graphcrudignore"),
                "src/main/java/com/acme/Ignored.java\n",
                StandardCharsets.UTF_8);
        JavaSourceAnalyzer analyzer = new EclipseJdtJavaSourceAnalyzer();
        var input = new JavaAnalysisInput(
                new ProjectId("orders"),
                new SnapshotId("snapshot-ignore"),
                projectRoot,
                new JavaBuildMetadata(
                        "app", Map.of(sourceRoot, StandardCharsets.UTF_8), List.of(), true));

        var sourceFacts = analyzer.analyze(input).facts().stream()
                .filter(NodeFact.class::isInstance)
                .map(NodeFact.class::cast)
                .filter(fact -> fact.id().kind() == NodeKind.SOURCE_FILE)
                .toList();

        assertEquals(List.of("src/main/java/com/acme/Included.java"), sourceFacts.stream()
                .map(fact -> fact.id().identityParts().get("path"))
                .toList());
    }

    @Test
    void relative_classpath_metadata_is_rejected_instead_of_resolved_by_a_build_tool() throws Exception {
        var sourceRoot = projectRoot.resolve("src/main/java");
        writeJava(sourceRoot.resolve("Example.java"), "public class Example {}");

        assertThrows(IllegalArgumentException.class, () -> new JavaBuildMetadata(
                "app",
                Map.of(sourceRoot, StandardCharsets.UTF_8),
                List.of(Path.of("infrastructure")),
                true));
    }

    @Test
    void direct_java_call_reaches_a_constant_direct_jdbc_sql_statement() throws Exception {
        var sourceRoot = projectRoot.resolve("src/main/java");
        writeJava(sourceRoot.resolve("com/acme/Orders.java"), """
                package com.acme;

                import java.sql.Connection;

                public class Orders {
                    private Connection connection;

                    public void entry() throws Exception {
                        persist();
                    }

                    private void persist() throws Exception {
                        connection.prepareStatement("select * from orders");
                    }
                }
                """);
        JavaSourceAnalyzer analyzer = new EclipseJdtJavaSourceAnalyzer();
        var input = new JavaAnalysisInput(
                new ProjectId("orders"),
                new SnapshotId("snapshot-jdbc"),
                projectRoot,
                new JavaBuildMetadata(
                        "app", Map.of(sourceRoot, StandardCharsets.UTF_8), List.of(), true));
        var entry = NodeId.javaMethod("orders", "app", "java", "com.acme.Orders", "entry", "()V");
        var persist = NodeId.javaMethod("orders", "app", "java", "com.acme.Orders", "persist", "()V");
        var sql = NodeId.of(NodeKind.SQL_STATEMENT, Map.of(
                "module", "app",
                "ordinal", "1",
                "owner", persist.canonicalValue(),
                "project", "orders",
                "sql", "select * from orders"));

        var first = analyzer.analyze(input).facts();
        var second = analyzer.analyze(input).facts();
        var assertions = first.stream()
                .filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .toList();
        var evidenceSubjects = first.stream()
                .filter(EvidenceOccurrence.class::isInstance)
                .map(EvidenceOccurrence.class::cast)
                .map(EvidenceOccurrence::subject)
                .toList();

        assertEquals(first, second);
        assertTrue(first.stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .anyMatch(fact -> fact.id().equals(sql)
                        && fact.properties().get("sql").equals("select * from orders")));
        assertTrue(assertions.contains(RelationshipAssertion.of(entry, RelationshipType.CALLS, persist, Map.of())));
        assertTrue(assertions.contains(RelationshipAssertion.of(persist, RelationshipType.EXECUTES, sql, Map.of())));
        assertTrue(evidenceSubjects.contains(sql));
        assertTrue(assertions.stream().map(RelationshipAssertion::id).allMatch(evidenceSubjects::contains));
        assertTrue(first.stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .noneMatch(fact -> fact.id().kind() == NodeKind.TABLE));
        assertTrue(assertions.stream().noneMatch(assertion -> assertion.type() == RelationshipType.READS));
    }

    @Test
    void cyclic_calls_are_bounded_by_call_sites_without_duplicating_canonical_edges() throws Exception {
        var sourceRoot = projectRoot.resolve("src/main/java");
        writeJava(sourceRoot.resolve("com/acme/Cycle.java"), """
                package com.acme;

                public class Cycle {
                    void first() {
                        second();
                        second();
                    }

                    void second() {
                        first();
                    }

                    void direct() {
                        direct();
                    }
                }
                """);
        JavaSourceAnalyzer analyzer = new EclipseJdtJavaSourceAnalyzer();
        var input = new JavaAnalysisInput(
                new ProjectId("cycles"),
                new SnapshotId("snapshot-cycle"),
                projectRoot,
                new JavaBuildMetadata(
                        "app", Map.of(sourceRoot, StandardCharsets.UTF_8), List.of(), true));

        var first = analyzer.analyze(input).facts();
        var second = analyzer.analyze(input).facts();
        var calls = first.stream()
                .filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .filter(assertion -> assertion.type() == RelationshipType.CALLS)
                .toList();
        var callEvidence = first.stream()
                .filter(EvidenceOccurrence.class::isInstance)
                .map(EvidenceOccurrence.class::cast)
                .filter(evidence -> calls.stream().anyMatch(call -> call.id().equals(evidence.subject())))
                .toList();

        assertEquals(first, second);
        assertEquals(3, calls.size());
        assertEquals(3, calls.stream().map(RelationshipAssertion::id).distinct().count());
        assertEquals(4, callEvidence.size());
    }

    @Test
    void jdbc_constants_are_confirmed_while_runtime_sql_is_retained_as_unresolved() throws Exception {
        var sourceRoot = projectRoot.resolve("src/main/java");
        writeJava(sourceRoot.resolve("com/acme/Queries.java"), """
                package com.acme;

                import java.sql.Connection;

                public class Queries {
                    private static final String SQL = "select * " + "from orders";
                    private static final String TEXT_BLOCK = \"""
                            select id from orders
                            \""";
                    private Connection connection;

                    void run(String table) throws Exception {
                        connection.prepareStatement(SQL);
                        connection.prepareStatement(TEXT_BLOCK);
                        connection.prepareStatement("select * from " + table);
                    }
                }
                """);
        JavaSourceAnalyzer analyzer = new EclipseJdtJavaSourceAnalyzer();
        var input = new JavaAnalysisInput(
                new ProjectId("queries"),
                new SnapshotId("snapshot-sql-boundary"),
                projectRoot,
                new JavaBuildMetadata(
                        "app", Map.of(sourceRoot, StandardCharsets.UTF_8), List.of(), true));

        var firstResult = analyzer.analyze(input);
        assertEquals(SnapshotCompletion.PARTIAL, firstResult.completion());
        var first = firstResult.facts();
        var second = analyzer.analyze(input).facts();
        assertEquals(first, second);
        assertEquals(canonicalHash(first), canonicalHash(second));
        var facts = first;
        var sqlFacts = facts.stream()
                .filter(NodeFact.class::isInstance)
                .map(NodeFact.class::cast)
                .filter(fact -> fact.id().kind() == NodeKind.SQL_STATEMENT)
                .toList();
        var jdbcCalls = facts.stream()
                .filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .filter(assertion -> assertion.type() == RelationshipType.CALLS)
                .filter(assertion -> assertion.target().canonicalValue().contains("java.sql.Connection"))
                .toList();

        assertEquals(3, sqlFacts.size());
        assertTrue(sqlFacts.stream().anyMatch(fact -> "select * from orders".equals(fact.properties().get("sql"))));
        assertTrue(sqlFacts.stream().anyMatch(fact -> fact.properties().getOrDefault("sql", "")
                .contains("select id from orders")));
        var unresolved = sqlFacts.stream()
                .filter(fact -> fact.properties().containsKey("expression"))
                .findFirst()
                .orElseThrow();
        assertTrue(unresolved.properties().get("expression").contains("table"));
        assertTrue(facts.stream().filter(EvidenceOccurrence.class::isInstance).map(EvidenceOccurrence.class::cast)
                .anyMatch(evidence -> evidence.subject().equals(unresolved.id())
                        && evidence.evidenceLevel() == io.graphcrud.model.EvidenceLevel.UNRESOLVED
                        && evidence.explanation().startsWith("JAVA_SQL_RUNTIME_DEPENDENT:")));
        assertTrue(jdbcCalls.isEmpty());
    }

    @Test
    void missing_dependency_marks_analysis_partial_without_losing_unrelated_confirmed_facts() throws Exception {
        var sourceRoot = projectRoot.resolve("src/main/java");
        writeJava(sourceRoot.resolve("com/acme/Mixed.java"), """
                package com.acme;

                import com.missing.Client;
                import java.sql.Connection;

                public class Mixed {
                    private Client client;
                    private Connection connection;

                    void broken() {
                        client.send();
                    }

                    void healthy() throws Exception {
                        connection.prepareStatement("select 1");
                    }
                }
                """);
        JavaSourceAnalyzer analyzer = new EclipseJdtJavaSourceAnalyzer();
        var input = new JavaAnalysisInput(
                new ProjectId("partial"),
                new SnapshotId("snapshot-partial"),
                projectRoot,
                new JavaBuildMetadata(
                        "app", Map.of(sourceRoot, StandardCharsets.UTF_8), List.of(), true));
        var broken = NodeId.javaMethod("partial", "app", "java", "com.acme.Mixed", "broken", "()V");

        var first = analyzer.analyze(input);
        var second = analyzer.analyze(input);

        assertEquals(first, second);
        assertEquals(SnapshotCompletion.PARTIAL, first.completion());
        assertTrue(first.facts().stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .anyMatch(fact -> fact.id().kind() == NodeKind.SQL_STATEMENT
                        && "select 1".equals(fact.properties().get("sql"))));
        assertTrue(first.facts().stream().filter(EvidenceOccurrence.class::isInstance)
                .map(EvidenceOccurrence.class::cast)
                .anyMatch(evidence -> evidence.subject().equals(broken)
                        && evidence.evidenceLevel() == io.graphcrud.model.EvidenceLevel.UNRESOLVED
                        && evidence.explanation().startsWith("JAVA_DEPENDENCY_MISSING:")
                        && evidence.explanation().contains("client.send()")
                        && evidence.explanation().contains("problemId=")
                        && evidence.explanation().contains("severity=ERROR")
                        && evidence.explanation().contains("candidates=")));
    }

    @Test
    void polymorphic_calls_retain_confirmed_declaration_and_possible_source_implementations() throws Exception {
        var sourceRoot = projectRoot.resolve("src/main/java");
        writeJava(sourceRoot.resolve("com/acme/Task.java"),
                "package com.acme; public interface Task { void run(); }");
        writeJava(sourceRoot.resolve("com/acme/Alpha.java"),
                "package com.acme; public class Alpha implements Task { public void run() {} }");
        writeJava(sourceRoot.resolve("com/acme/Beta.java"),
                "package com.acme; public class Beta implements Task { public void run() {} }");
        writeJava(sourceRoot.resolve("com/acme/Caller.java"), """
                package com.acme;
                public class Caller {
                    void dispatch(Task task) { task.run(); }
                }
                """);
        JavaSourceAnalyzer analyzer = new EclipseJdtJavaSourceAnalyzer();
        var input = new JavaAnalysisInput(
                new ProjectId("polymorphism"),
                new SnapshotId("snapshot-polymorphism"),
                projectRoot,
                new JavaBuildMetadata(
                        "app", Map.of(sourceRoot, StandardCharsets.UTF_8), List.of(), true));
        var caller = NodeId.javaMethod(
                "polymorphism", "app", "java", "com.acme.Caller", "dispatch", "(Lcom/acme/Task;)V");
        var declaration = NodeId.javaMethod(
                "polymorphism", "app", "java", "com.acme.Task", "run", "()V");
        var alpha = NodeId.javaMethod(
                "polymorphism", "app", "java", "com.acme.Alpha", "run", "()V");
        var beta = NodeId.javaMethod(
                "polymorphism", "app", "java", "com.acme.Beta", "run", "()V");

        var first = analyzer.analyze(input);
        var second = analyzer.analyze(input);
        var declaredCall = RelationshipAssertion.of(caller, RelationshipType.CALLS, declaration, Map.of());
        var alphaCall = RelationshipAssertion.of(caller, RelationshipType.CALLS, alpha, Map.of());
        var betaCall = RelationshipAssertion.of(caller, RelationshipType.CALLS, beta, Map.of());

        assertEquals(first, second);
        assertTrue(first.facts().containsAll(List.of(declaredCall, alphaCall, betaCall)));
        assertTrue(hasEvidence(first.facts(), declaredCall, io.graphcrud.model.EvidenceLevel.CONFIRMED));
        assertTrue(hasEvidence(first.facts(), alphaCall, io.graphcrud.model.EvidenceLevel.POSSIBLE));
        assertTrue(hasEvidence(first.facts(), betaCall, io.graphcrud.model.EvidenceLevel.POSSIBLE));
        assertTrue(!hasEvidence(first.facts(), alphaCall, io.graphcrud.model.EvidenceLevel.CONFIRMED));
        assertTrue(!hasEvidence(first.facts(), betaCall, io.graphcrud.model.EvidenceLevel.CONFIRMED));
    }

    @Test
    void spring_http_constructor_injection_reaches_confirmed_jdbc_template_sql() throws Exception {
        var sourceRoot = projectRoot.resolve("src/main/java");
        writeSpringFixtureTypes(sourceRoot);
        writeJava(sourceRoot.resolve("com/acme/OrderService.java"), """
                package com.acme;
                import org.springframework.jdbc.core.JdbcTemplate;
                import org.springframework.stereotype.Service;
                @Service
                public class OrderService {
                    private final JdbcTemplate jdbc;
                    public OrderService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
                    public void create() { jdbc.update("insert into orders(id) values (1)"); }
                }
                """);
        writeJava(sourceRoot.resolve("com/acme/OrderController.java"), """
                package com.acme;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                @RequestMapping("/orders")
                public class OrderController {
                    private final OrderService service;
                    public OrderController(OrderService service) { this.service = service; }
                    @PostMapping("/{id}")
                    public void create() { service.create(); }
                    public void ordinary() {}
                }
                """);
        JavaSourceAnalyzer analyzer = new EclipseJdtJavaSourceAnalyzer();
        var input = new JavaAnalysisInput(
                new ProjectId("spring-http"),
                new SnapshotId("snapshot-spring-http"),
                projectRoot,
                new JavaBuildMetadata(
                        "app", Map.of(sourceRoot, StandardCharsets.UTF_8), List.of(), true));
        var handler = NodeId.javaMethod(
                "spring-http", "app", "java", "com.acme.OrderController", "create", "()V");
        var ordinary = NodeId.javaMethod(
                "spring-http", "app", "java", "com.acme.OrderController", "ordinary", "()V");
        var service = NodeId.javaMethod(
                "spring-http", "app", "java", "com.acme.OrderService", "create", "()V");

        var result = analyzer.analyze(input);
        var endpoint = result.facts().stream()
                .filter(NodeFact.class::isInstance)
                .map(NodeFact.class::cast)
                .filter(fact -> fact.id().kind() == NodeKind.HTTP_ENDPOINT_BINDING)
                .findFirst()
                .orElseThrow();
        var sql = result.facts().stream()
                .filter(NodeFact.class::isInstance)
                .map(NodeFact.class::cast)
                .filter(fact -> fact.id().kind() == NodeKind.SQL_STATEMENT)
                .filter(fact -> "insert into orders(id) values (1)".equals(fact.properties().get("sql")))
                .findFirst()
                .orElseThrow();
        var routesToHandler = RelationshipAssertion.of(
                endpoint.id(), RelationshipType.ROUTES_TO, handler, Map.of());

        assertEquals("POST", endpoint.properties().get("httpMethod"));
        assertEquals("/orders/{id}", endpoint.properties().get("rawRoute"));
        assertEquals("/orders/{}", endpoint.properties().get("routeShape"));
        assertEquals(List.of(6, 10), result.facts().stream()
                .filter(EvidenceOccurrence.class::isInstance)
                .map(EvidenceOccurrence.class::cast)
                .filter(evidence -> evidence.subject().equals(endpoint.id()))
                .map(evidence -> evidence.sourceAnchor().line())
                .sorted()
                .toList());
        assertTrue(result.facts().contains(routesToHandler));
        assertTrue(hasEvidence(result.facts(), routesToHandler, io.graphcrud.model.EvidenceLevel.CONFIRMED));
        var injection = result.facts().stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .filter(assertion -> assertion.type() == RelationshipType.INJECTS)
                .findFirst()
                .orElseThrow();
        assertTrue(hasEvidence(result.facts(), injection, io.graphcrud.model.EvidenceLevel.CONFIRMED));
        assertTrue(result.facts().contains(RelationshipAssertion.of(
                handler, RelationshipType.CALLS, service, Map.of())));
        assertTrue(result.facts().stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .anyMatch(assertion -> assertion.type() == RelationshipType.EXECUTES
                        && assertion.target().equals(sql.id())));
        assertTrue(result.facts().stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .noneMatch(assertion -> assertion.type() == RelationshipType.ROUTES_TO
                        && assertion.target().equals(ordinary)));
    }

    @Test
    void registered_servlet_filter_with_unique_autowired_collaborator_reaches_sql() throws Exception {
        var sourceRoot = projectRoot.resolve("src/main/java");
        writeFilterFixtureTypes(sourceRoot);
        writeJava(sourceRoot.resolve("com/acme/AuditService.java"), """
                package com.acme;
                import org.springframework.jdbc.core.JdbcTemplate;
                import org.springframework.stereotype.Service;
                @Service
                public class AuditService {
                    private final JdbcTemplate jdbc;
                    public AuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
                    public void audit() { jdbc.update("insert into audit_log(id) values (1)"); }
                }
                """);
        writeJava(sourceRoot.resolve("com/acme/AuditFilter.java"), """
                package com.acme;
                import jakarta.servlet.*;
                import jakarta.servlet.annotation.WebFilter;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.stereotype.Component;
                @Component
                @WebFilter("/api/*")
                public class AuditFilter implements Filter {
                    @Autowired private AuditService service;
                    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
                        service.audit();
                    }
                    public void ordinary() {}
                }
                """);
        writeJava(sourceRoot.resolve("com/acme/UnregisteredFilter.java"), """
                package com.acme;
                import jakarta.servlet.*;
                public class UnregisteredFilter implements Filter {
                    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {}
                }
                """);
        JavaSourceAnalyzer analyzer = new EclipseJdtJavaSourceAnalyzer();
        var input = new JavaAnalysisInput(
                new ProjectId("spring-filter"),
                new SnapshotId("snapshot-spring-filter"),
                projectRoot,
                new JavaBuildMetadata(
                        "app", Map.of(sourceRoot, StandardCharsets.UTF_8), List.of(), true));
        var registered = NodeId.javaMethod(
                "spring-filter", "app", "java", "com.acme.AuditFilter", "doFilter",
                "(Ljakarta/servlet/ServletRequest;Ljakarta/servlet/ServletResponse;Ljakarta/servlet/FilterChain;)V");
        var unregistered = NodeId.javaMethod(
                "spring-filter", "app", "java", "com.acme.UnregisteredFilter", "doFilter",
                "(Ljakarta/servlet/ServletRequest;Ljakarta/servlet/ServletResponse;Ljakarta/servlet/FilterChain;)V");

        var result = analyzer.analyze(input);
        var filterSource = result.facts().stream()
                .filter(NodeFact.class::isInstance)
                .map(NodeFact.class::cast)
                .filter(fact -> fact.id().kind() == NodeKind.INVOCATION_SOURCE)
                .filter(fact -> "servlet-filter".equals(fact.properties().get("invocationKind")))
                .findFirst()
                .orElseThrow();
        var binding = RelationshipAssertion.of(filterSource.id(), RelationshipType.ROUTES_TO, registered, Map.of());

        assertEquals("/api/*", filterSource.properties().get("urlPattern"));
        assertTrue(result.facts().contains(binding));
        assertTrue(hasEvidence(result.facts(), binding, io.graphcrud.model.EvidenceLevel.CONFIRMED));
        var injection = result.facts().stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .filter(assertion -> assertion.type() == RelationshipType.INJECTS
                        && assertion.source().equals(registered))
                .findFirst()
                .orElseThrow();
        assertTrue(hasEvidence(result.facts(), injection, io.graphcrud.model.EvidenceLevel.CONFIRMED));
        assertTrue(result.facts().stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .anyMatch(fact -> fact.id().kind() == NodeKind.SQL_STATEMENT
                        && "insert into audit_log(id) values (1)".equals(fact.properties().get("sql"))));
        assertTrue(result.facts().stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .noneMatch(assertion -> assertion.type() == RelationshipType.ROUTES_TO
                        && assertion.target().equals(unregistered)));
    }

    @Test
    void ambiguous_autowired_filter_collaborators_remain_possible() throws Exception {
        var sourceRoot = projectRoot.resolve("src/main/java");
        writeFilterFixtureTypes(sourceRoot);
        writeJava(sourceRoot.resolve("com/acme/Auditor.java"),
                "package com.acme; public interface Auditor { void audit(); }");
        writeJava(sourceRoot.resolve("com/acme/FirstAuditor.java"), """
                package com.acme;
                import org.springframework.stereotype.Service;
                @Service public class FirstAuditor implements Auditor {
                    public FirstAuditor() {}
                    public void audit() {}
                }
                """);
        writeJava(sourceRoot.resolve("com/acme/SecondAuditor.java"), """
                package com.acme;
                import org.springframework.stereotype.Service;
                @Service public class SecondAuditor implements Auditor {
                    public SecondAuditor() {}
                    public void audit() {}
                }
                """);
        writeJava(sourceRoot.resolve("com/acme/AmbiguousFilter.java"), """
                package com.acme;
                import jakarta.servlet.*;
                import jakarta.servlet.annotation.WebFilter;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.stereotype.Component;
                @Component @WebFilter("/ambiguous/*")
                public class AmbiguousFilter implements Filter {
                    @Autowired private Auditor auditor;
                    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
                        auditor.audit();
                    }
                }
                """);
        JavaSourceAnalyzer analyzer = new EclipseJdtJavaSourceAnalyzer();
        var input = new JavaAnalysisInput(
                new ProjectId("ambiguous-filter"),
                new SnapshotId("snapshot-ambiguous-filter"),
                projectRoot,
                new JavaBuildMetadata(
                        "app", Map.of(sourceRoot, StandardCharsets.UTF_8), List.of(), true));
        var doFilter = NodeId.javaMethod(
                "ambiguous-filter", "app", "java", "com.acme.AmbiguousFilter", "doFilter",
                "(Ljakarta/servlet/ServletRequest;Ljakarta/servlet/ServletResponse;Ljakarta/servlet/FilterChain;)V");

        var facts = analyzer.analyze(input).facts();
        var injections = facts.stream()
                .filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .filter(assertion -> assertion.type() == RelationshipType.INJECTS
                        && assertion.source().equals(doFilter))
                .toList();

        assertEquals(2, injections.size());
        assertTrue(injections.stream().allMatch(assertion ->
                hasEvidence(facts, assertion, io.graphcrud.model.EvidenceLevel.POSSIBLE)));
        assertTrue(injections.stream().noneMatch(assertion ->
                hasEvidence(facts, assertion, io.graphcrud.model.EvidenceLevel.CONFIRMED)));
    }

    @Test
    void scheduled_method_uses_named_resource_and_retains_missing_resource() throws Exception {
        var sourceRoot = projectRoot.resolve("src/main/java");
        writeScheduledFixtureTypes(sourceRoot);
        writeJava(sourceRoot.resolve("com/acme/SchedulingConfig.java"), """
                package com.acme;
                import org.springframework.scheduling.annotation.EnableScheduling;
                @EnableScheduling public class SchedulingConfig {}
                """);
        writeJava(sourceRoot.resolve("com/acme/JobService.java"), """
                package com.acme;
                import org.springframework.jdbc.core.JdbcTemplate;
                import org.springframework.stereotype.Service;
                @Service("jobService") public class JobService {
                    private final JdbcTemplate jdbc;
                    public JobService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
                    public void run() { jdbc.update("delete from job_queue"); }
                }
                """);
        writeJava(sourceRoot.resolve("com/acme/Jobs.java"), """
                package com.acme;
                import jakarta.annotation.Resource;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;
                @Component public class Jobs {
                    @Resource(name="jobService") private JobService service;
                    @Resource(name="missingService") private JobService missing;
                    @Scheduled(fixedDelay="1000") public void poll() { service.run(); }
                }
                """);
        JavaSourceAnalyzer analyzer = new EclipseJdtJavaSourceAnalyzer();
        var input = new JavaAnalysisInput(
                new ProjectId("scheduled"), new SnapshotId("snapshot-scheduled"), projectRoot,
                new JavaBuildMetadata("app", Map.of(sourceRoot, StandardCharsets.UTF_8), List.of(), true));
        var poll = NodeId.javaMethod("scheduled", "app", "java", "com.acme.Jobs", "poll", "()V");

        var facts = analyzer.analyze(input).facts();
        var source = facts.stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .filter(fact -> fact.id().kind() == NodeKind.INVOCATION_SOURCE)
                .filter(fact -> "scheduled".equals(fact.properties().get("invocationKind")))
                .findFirst().orElseThrow();
        var binding = RelationshipAssertion.of(source.id(), RelationshipType.ROUTES_TO, poll, Map.of());

        assertTrue(facts.contains(binding));
        assertTrue(hasEvidence(facts, binding, io.graphcrud.model.EvidenceLevel.CONFIRMED));
        assertTrue(facts.stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .anyMatch(assertion -> assertion.type() == RelationshipType.INJECTS
                        && assertion.source().equals(poll)));
        assertTrue(facts.stream().filter(EvidenceOccurrence.class::isInstance)
                .map(EvidenceOccurrence.class::cast)
                .anyMatch(evidence -> evidence.subject().equals(poll)
                        && evidence.evidenceLevel() == io.graphcrud.model.EvidenceLevel.UNRESOLVED
                        && evidence.explanation().startsWith("SPRING_RESOURCE_MISSING:")));
        assertTrue(facts.stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .anyMatch(fact -> fact.id().kind() == NodeKind.SQL_STATEMENT
                        && "delete from job_queue".equals(fact.properties().get("sql"))));
    }

    @Test
    void resource_without_explicit_name_prefers_the_field_name_before_type_fallback() throws Exception {
        var sourceRoot = projectRoot.resolve("src/main/java");
        writeScheduledFixtureTypes(sourceRoot);
        writeJava(sourceRoot.resolve("com/acme/Config.java"), """
                package com.acme;
                import org.springframework.scheduling.annotation.EnableScheduling;
                @EnableScheduling class Config {}
                """);
        writeJava(sourceRoot.resolve("com/acme/Worker.java"),
                "package com.acme; public interface Worker { void run(); }");
        writeJava(sourceRoot.resolve("com/acme/NamedWorker.java"), """
                package com.acme;
                import org.springframework.stereotype.Service;
                @Service("jobService") public class NamedWorker implements Worker {
                    public NamedWorker() {} public void run() {}
                }
                """);
        writeJava(sourceRoot.resolve("com/acme/OtherWorker.java"), """
                package com.acme;
                import org.springframework.stereotype.Service;
                @Service("other") public class OtherWorker implements Worker {
                    public OtherWorker() {} public void run() {}
                }
                """);
        writeJava(sourceRoot.resolve("com/acme/NamedJob.java"), """
                package com.acme;
                import jakarta.annotation.Resource;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;
                @Component public class NamedJob {
                    @Resource private Worker jobService;
                    @Scheduled(fixedDelay="1") public void poll() { jobService.run(); }
                }
                """);
        JavaSourceAnalyzer analyzer = new EclipseJdtJavaSourceAnalyzer();
        var input = new JavaAnalysisInput(
                new ProjectId("resource-default"), new SnapshotId("snapshot-resource-default"), projectRoot,
                new JavaBuildMetadata("app", Map.of(sourceRoot, StandardCharsets.UTF_8), List.of(), true));
        var poll = NodeId.javaMethod("resource-default", "app", "java", "com.acme.NamedJob", "poll", "()V");

        var facts = analyzer.analyze(input).facts();
        var injections = facts.stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .filter(assertion -> assertion.type() == RelationshipType.INJECTS
                        && assertion.source().equals(poll)).toList();

        assertEquals(1, injections.size());
        assertTrue(injections.get(0).target().identityParts().get("declaringType").equals("com.acme.NamedWorker"));
        assertTrue(hasEvidence(facts, injections.get(0), io.graphcrud.model.EvidenceLevel.CONFIRMED));
    }

    @Test
    void supported_startup_sources_bind_main_and_boot_runners_to_sql_paths() throws Exception {
        var sourceRoot = projectRoot.resolve("src/main/java");
        writeStartupFixtureTypes(sourceRoot);
        writeJava(sourceRoot.resolve("com/acme/StartupSql.java"), """
                package com.acme;
                import org.springframework.jdbc.core.JdbcTemplate;
                public class StartupSql {
                    private static JdbcTemplate jdbc;
                    public static void write() { jdbc.update("insert into startup_log(id) values (1)"); }
                }
                """);
        writeJava(sourceRoot.resolve("com/acme/MainApp.java"), """
                package com.acme;
                public class MainApp {
                    public static void main(String[] args) { StartupSql.write(); }
                    public void ordinary() {}
                }
                """);
        writeJava(sourceRoot.resolve("com/acme/AppRunner.java"), """
                package com.acme;
                import org.springframework.boot.ApplicationArguments;
                import org.springframework.boot.ApplicationRunner;
                import org.springframework.stereotype.Component;
                @Component public class AppRunner implements ApplicationRunner {
                    public void run(ApplicationArguments args) { StartupSql.write(); }
                }
                """);
        writeJava(sourceRoot.resolve("com/acme/CliRunner.java"), """
                package com.acme;
                import org.springframework.boot.CommandLineRunner;
                import org.springframework.stereotype.Component;
                @Component public class CliRunner implements CommandLineRunner {
                    public void run(String... args) { StartupSql.write(); }
                }
                """);
        JavaSourceAnalyzer analyzer = new EclipseJdtJavaSourceAnalyzer();
        var input = new JavaAnalysisInput(
                new ProjectId("startup"), new SnapshotId("snapshot-startup"), projectRoot,
                new JavaBuildMetadata("app", Map.of(sourceRoot, StandardCharsets.UTF_8), List.of(), true));

        var facts = analyzer.analyze(input).facts();
        var startupSources = facts.stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .filter(fact -> fact.id().kind() == NodeKind.INVOCATION_SOURCE)
                .filter(fact -> Set.of("main", "application-runner", "command-line-runner")
                        .contains(fact.properties().get("invocationKind")))
                .toList();

        assertEquals(3, startupSources.size());
        assertTrue(startupSources.stream().allMatch(source -> facts.stream()
                .filter(RelationshipAssertion.class::isInstance).map(RelationshipAssertion.class::cast)
                .anyMatch(assertion -> assertion.type() == RelationshipType.ROUTES_TO
                        && assertion.source().equals(source.id())
                        && hasEvidence(facts, assertion, io.graphcrud.model.EvidenceLevel.CONFIRMED))));
        assertTrue(facts.stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .anyMatch(fact -> fact.id().kind() == NodeKind.SQL_STATEMENT
                        && "insert into startup_log(id) values (1)".equals(fact.properties().get("sql"))));
    }

    @Test
    void spring_event_listener_binds_resolved_event_and_retains_missing_event_type() throws Exception {
        var sourceRoot = projectRoot.resolve("src/main/java");
        writeEventFixtureTypes(sourceRoot);
        writeJava(sourceRoot.resolve("com/acme/OrderEvent.java"),
                "package com.acme; public class OrderEvent {}");
        writeJava(sourceRoot.resolve("com/acme/SecondEvent.java"),
                "package com.acme; public class SecondEvent {}");
        writeJava(sourceRoot.resolve("com/acme/OrderEvents.java"), """
                package com.acme;
                import org.springframework.context.event.EventListener;
                import org.springframework.jdbc.core.JdbcTemplate;
                import org.springframework.stereotype.Component;
                @Component public class OrderEvents {
                    private JdbcTemplate jdbc;
                    @EventListener public void onOrder(OrderEvent event) {
                        jdbc.update("insert into event_log(id) values (1)");
                    }
                    @EventListener(classes={OrderEvent.class, SecondEvent.class}) public void onSeveral() {}
                    @EventListener(classes=MissingEvent.class) public void onMissing() {}
                    public void ordinary() {}
                }
                """);
        JavaSourceAnalyzer analyzer = new EclipseJdtJavaSourceAnalyzer();
        var input = new JavaAnalysisInput(
                new ProjectId("events"), new SnapshotId("snapshot-events"), projectRoot,
                new JavaBuildMetadata("app", Map.of(sourceRoot, StandardCharsets.UTF_8), List.of(), true));
        var listener = NodeId.javaMethod(
                "events", "app", "java", "com.acme.OrderEvents", "onOrder", "(Lcom/acme/OrderEvent;)V");
        var missing = NodeId.javaMethod(
                "events", "app", "java", "com.acme.OrderEvents", "onMissing", "()V");
        var several = NodeId.javaMethod(
                "events", "app", "java", "com.acme.OrderEvents", "onSeveral", "()V");

        var facts = analyzer.analyze(input).facts();
        var source = facts.stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .filter(fact -> fact.id().kind() == NodeKind.INVOCATION_SOURCE)
                .filter(fact -> "spring-event".equals(fact.properties().get("invocationKind")))
                .filter(fact -> listener.canonicalValue().equals(fact.id().identityParts().get("target")))
                .findFirst().orElseThrow();
        var binding = RelationshipAssertion.of(source.id(), RelationshipType.ROUTES_TO, listener, Map.of());

        assertEquals("com.acme.OrderEvent", source.properties().get("eventType"));
        assertTrue(facts.contains(binding));
        assertTrue(hasEvidence(facts, binding, io.graphcrud.model.EvidenceLevel.CONFIRMED));
        assertEquals(2, facts.stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .filter(assertion -> assertion.type() == RelationshipType.ROUTES_TO
                        && assertion.target().equals(several)
                        && assertion.source().kind() == NodeKind.INVOCATION_SOURCE)
                .count());
        assertTrue(facts.stream().filter(EvidenceOccurrence.class::isInstance)
                .map(EvidenceOccurrence.class::cast)
                .anyMatch(evidence -> evidence.subject().equals(missing)
                        && evidence.evidenceLevel() == io.graphcrud.model.EvidenceLevel.UNRESOLVED
                        && evidence.explanation().startsWith("SPRING_EVENT_TYPE_UNRESOLVED:")));
        assertTrue(facts.stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .anyMatch(fact -> fact.id().kind() == NodeKind.SQL_STATEMENT
                        && "insert into event_log(id) values (1)".equals(fact.properties().get("sql"))));
    }

    @Test
    void explicit_consumer_manifest_binds_only_its_named_java_target() throws Exception {
        var sourceRoot = projectRoot.resolve("src/main/java");
        writeJava(sourceRoot.resolve("org/springframework/jdbc/core/JdbcTemplate.java"),
                "package org.springframework.jdbc.core; public class JdbcTemplate { public int update(String sql) { return 0; } }");
        writeJava(sourceRoot.resolve("com/acme/OrderConsumer.java"), """
                package com.acme;
                import org.springframework.jdbc.core.JdbcTemplate;
                public class OrderConsumer {
                    private JdbcTemplate jdbc;
                    public void receive() { jdbc.update("insert into inbox(id) values (1)"); }
                    public void ordinary() {}
                }
                """);
        Files.writeString(projectRoot.resolve(".graphcrud-consumers"),
                "orders=com.acme.OrderConsumer#receive\n", StandardCharsets.UTF_8);
        JavaSourceAnalyzer analyzer = new EclipseJdtJavaSourceAnalyzer();
        var input = new JavaAnalysisInput(
                new ProjectId("consumers"), new SnapshotId("snapshot-consumers"), projectRoot,
                new JavaBuildMetadata("app", Map.of(sourceRoot, StandardCharsets.UTF_8), List.of(), true));
        var receive = NodeId.javaMethod("consumers", "app", "java", "com.acme.OrderConsumer", "receive", "()V");
        var ordinary = NodeId.javaMethod("consumers", "app", "java", "com.acme.OrderConsumer", "ordinary", "()V");

        var facts = analyzer.analyze(input).facts();
        var entry = facts.stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .filter(fact -> fact.id().kind() == NodeKind.CONFIGURATION_ENTRY)
                .findFirst().orElseThrow();
        var source = facts.stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .filter(fact -> fact.id().kind() == NodeKind.INVOCATION_SOURCE)
                .filter(fact -> "configured-consumer".equals(fact.properties().get("invocationKind")))
                .findFirst().orElseThrow();
        var binding = RelationshipAssertion.of(source.id(), RelationshipType.ROUTES_TO, receive, Map.of());

        assertEquals("com.acme.OrderConsumer#receive", entry.properties().get("rawTarget"));
        assertTrue(facts.contains(binding));
        assertTrue(hasEvidence(facts, binding, io.graphcrud.model.EvidenceLevel.CONFIRMED));
        assertTrue(facts.stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .noneMatch(assertion -> assertion.type() == RelationshipType.ROUTES_TO
                        && assertion.target().equals(ordinary)));
        assertTrue(facts.stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .anyMatch(fact -> fact.id().kind() == NodeKind.SQL_STATEMENT
                        && "insert into inbox(id) values (1)".equals(fact.properties().get("sql"))));
    }

    @Test
    void named_parameter_jdbc_template_preserves_static_and_dynamic_sql_evidence() throws Exception {
        var sourceRoot = projectRoot.resolve("src/main/java");
        writeJava(sourceRoot.resolve("org/springframework/jdbc/core/namedparam/NamedParameterJdbcTemplate.java"), """
                package org.springframework.jdbc.core.namedparam;
                import java.util.Map;
                public class NamedParameterJdbcTemplate {
                    public int update(String sql, Map<String, ?> parameters) { return 0; }
                }
                """);
        writeJava(sourceRoot.resolve("com/acme/NamedQueries.java"), """
                package com.acme;
                import java.util.Map;
                import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
                public class NamedQueries {
                    private static final String SQL = "update orders " + "set status=:status";
                    private NamedParameterJdbcTemplate jdbc;
                    public void run(String table) {
                        jdbc.update(SQL, Map.of("status", "DONE"));
                        jdbc.update("update " + table + " set status=:status", Map.of("status", "DONE"));
                    }
                }
                """);
        JavaSourceAnalyzer analyzer = new EclipseJdtJavaSourceAnalyzer();
        var input = new JavaAnalysisInput(
                new ProjectId("named-jdbc"), new SnapshotId("snapshot-named-jdbc"), projectRoot,
                new JavaBuildMetadata("app", Map.of(sourceRoot, StandardCharsets.UTF_8), List.of(), true));

        var facts = analyzer.analyze(input).facts();
        var sqlFacts = facts.stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .filter(fact -> fact.id().kind() == NodeKind.SQL_STATEMENT).toList();

        assertEquals(2, sqlFacts.size());
        assertTrue(sqlFacts.stream().anyMatch(fact ->
                "update orders set status=:status".equals(fact.properties().get("sql"))));
        var dynamic = sqlFacts.stream().filter(fact -> fact.properties().containsKey("expression"))
                .findFirst().orElseThrow();
        assertTrue(dynamic.properties().get("expression").contains("table"));
        assertTrue(facts.stream().filter(EvidenceOccurrence.class::isInstance).map(EvidenceOccurrence.class::cast)
                .anyMatch(evidence -> evidence.subject().equals(dynamic.id())
                        && evidence.evidenceLevel() == io.graphcrud.model.EvidenceLevel.UNRESOLVED));
    }

    @Test
    void legacy_url_manifest_preserves_any_route_shape_and_class_only_resolution() throws Exception {
        var sourceRoot = projectRoot.resolve("src/main/java");
        writeJava(sourceRoot.resolve("com/acme/LegacyController.java"), """
                package com.acme;
                public class LegacyController { public void handle() {} }
                """);
        writeJava(sourceRoot.resolve("com/acme/AdminController.java"), """
                package com.acme;
                public class AdminController { public void first() {} public void second() {} }
                """);
        Files.writeString(projectRoot.resolve(".graphcrud-legacy-urls"), """
                /legacy/orders/{id}=com.acme.LegacyController#handle
                /legacy/admin/{name}=com.acme.AdminController
                """, StandardCharsets.UTF_8);
        JavaSourceAnalyzer analyzer = new EclipseJdtJavaSourceAnalyzer();
        var input = new JavaAnalysisInput(
                new ProjectId("legacy"), new SnapshotId("snapshot-legacy"), projectRoot,
                new JavaBuildMetadata("app", Map.of(sourceRoot, StandardCharsets.UTF_8), List.of(), true));
        var handle = NodeId.javaMethod("legacy", "app", "java", "com.acme.LegacyController", "handle", "()V");

        var facts = analyzer.analyze(input).facts();
        var sources = facts.stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .filter(fact -> fact.id().kind() == NodeKind.INVOCATION_SOURCE)
                .filter(fact -> "legacy-url".equals(fact.properties().get("invocationKind"))).toList();

        assertEquals(2, sources.size());
        assertTrue(sources.stream().allMatch(source -> "ANY".equals(source.properties().get("httpMethod"))));
        assertTrue(sources.stream().anyMatch(source -> "/legacy/orders/{id}".equals(source.properties().get("rawRoute"))
                && "/legacy/orders/{}".equals(source.properties().get("routeShape"))));
        assertTrue(facts.stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .anyMatch(assertion -> assertion.type() == RelationshipType.ROUTES_TO
                        && assertion.target().equals(handle)));
        assertTrue(facts.stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .anyMatch(fact -> fact.id().kind() == NodeKind.JAVA_TYPE
                        && "com.acme.AdminController".equals(fact.properties().get("qualifiedName"))));
        assertTrue(facts.stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .noneMatch(assertion -> assertion.type() == RelationshipType.ROUTES_TO
                        && assertion.target().kind() == NodeKind.CODE_SYMBOL
                        && assertion.target().identityParts().get("declaringType").equals("com.acme.AdminController")));
    }

    @Test
    void manual_ui_action_binds_only_the_explicit_java_target() throws Exception {
        var sourceRoot = projectRoot.resolve("src/main/java");
        writeJava(sourceRoot.resolve("com/acme/ScreenActions.java"), """
                package com.acme;
                public class ScreenActions {
                    public void save() {}
                    public void saveDraft() {}
                }
                """);
        Files.writeString(projectRoot.resolve(".graphcrud-ui-actions"),
                "Save Order=com.acme.ScreenActions#save\n", StandardCharsets.UTF_8);
        JavaSourceAnalyzer analyzer = new EclipseJdtJavaSourceAnalyzer();
        var input = new JavaAnalysisInput(
                new ProjectId("ui"), new SnapshotId("snapshot-ui"), projectRoot,
                new JavaBuildMetadata("app", Map.of(sourceRoot, StandardCharsets.UTF_8), List.of(), true));
        var save = NodeId.javaMethod("ui", "app", "java", "com.acme.ScreenActions", "save", "()V");
        var draft = NodeId.javaMethod("ui", "app", "java", "com.acme.ScreenActions", "saveDraft", "()V");

        var facts = analyzer.analyze(input).facts();
        var action = facts.stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .filter(fact -> fact.id().kind() == NodeKind.UI_ACTION).findFirst().orElseThrow();
        var binding = RelationshipAssertion.of(action.id(), RelationshipType.ROUTES_TO, save, Map.of());

        assertEquals("Save Order", action.properties().get("label"));
        assertEquals("com.acme.ScreenActions#save", action.properties().get("rawTarget"));
        assertTrue(facts.contains(binding));
        assertTrue(hasEvidence(facts, binding, io.graphcrud.model.EvidenceLevel.CONFIRMED));
        assertTrue(facts.stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .noneMatch(assertion -> assertion.type() == RelationshipType.ROUTES_TO
                        && assertion.target().equals(draft)));
    }

    @Test
    void runtime_spring_mapping_and_event_conditions_are_not_confirmed() throws Exception {
        var sourceRoot = projectRoot.resolve("src/main/java");
        writeJava(sourceRoot.resolve("org/springframework/stereotype/Component.java"),
                "package org.springframework.stereotype; public @interface Component {}");
        writeJava(sourceRoot.resolve("org/springframework/web/bind/annotation/RestController.java"),
                "package org.springframework.web.bind.annotation; public @interface RestController {}");
        writeJava(sourceRoot.resolve("org/springframework/web/bind/annotation/RequestMapping.java"),
                "package org.springframework.web.bind.annotation; public @interface RequestMapping { String[] value() default {}; }");
        writeJava(sourceRoot.resolve("org/springframework/web/bind/annotation/GetMapping.java"),
                "package org.springframework.web.bind.annotation; public @interface GetMapping { String[] value() default {}; }");
        writeJava(sourceRoot.resolve("org/springframework/context/event/EventListener.java"), """
                package org.springframework.context.event;
                public @interface EventListener {
                    Class<?>[] classes() default {};
                    String condition() default "";
                }
                """);
        writeJava(sourceRoot.resolve("com/acme/RuntimeSpring.java"), """
                package com.acme;
                import org.springframework.context.event.EventListener;
                import org.springframework.stereotype.Component;
                import org.springframework.web.bind.annotation.*;
                @RestController @RequestMapping("${base.path}")
                public class RuntimeSpring {
                    @GetMapping("/orders") public void dynamicRoute() {}
                    @EventListener(condition="#event.enabled") public void conditional(OrderEvent event) {}
                }
                @Component class ListenerMarker {}
                class OrderEvent { boolean enabled; }
                """);
        JavaSourceAnalyzer analyzer = new EclipseJdtJavaSourceAnalyzer();
        var input = new JavaAnalysisInput(
                new ProjectId("runtime-spring"), new SnapshotId("snapshot-runtime-spring"), projectRoot,
                new JavaBuildMetadata("app", Map.of(sourceRoot, StandardCharsets.UTF_8), List.of(), true));
        var route = NodeId.javaMethod("runtime-spring", "app", "java", "com.acme.RuntimeSpring", "dynamicRoute", "()V");
        var conditional = NodeId.javaMethod(
                "runtime-spring", "app", "java", "com.acme.RuntimeSpring", "conditional",
                "(Lcom/acme/OrderEvent;)V");

        var facts = analyzer.analyze(input).facts();

        assertTrue(facts.stream().filter(EvidenceOccurrence.class::isInstance).map(EvidenceOccurrence.class::cast)
                .anyMatch(evidence -> evidence.subject().equals(route)
                        && evidence.evidenceLevel() == io.graphcrud.model.EvidenceLevel.UNRESOLVED
                        && evidence.explanation().startsWith("SPRING_MAPPING_DYNAMIC:")));
        assertTrue(facts.stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .noneMatch(fact -> fact.id().kind() == NodeKind.HTTP_ENDPOINT_BINDING));
        var eventSource = facts.stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                .filter(fact -> fact.id().kind() == NodeKind.INVOCATION_SOURCE)
                .filter(fact -> "spring-event".equals(fact.properties().get("invocationKind")))
                .findFirst().orElseThrow();
        assertEquals("#event.enabled", eventSource.properties().get("condition"));
        assertTrue(facts.stream().filter(RelationshipAssertion.class::isInstance)
                .map(RelationshipAssertion.class::cast)
                .filter(assertion -> assertion.source().equals(eventSource.id()) && assertion.target().equals(conditional))
                .allMatch(assertion -> hasEvidence(facts, assertion, io.graphcrud.model.EvidenceLevel.POSSIBLE)
                        && !hasEvidence(facts, assertion, io.graphcrud.model.EvidenceLevel.CONFIRMED)));
    }

    private static void writeSpringFixtureTypes(Path sourceRoot) throws Exception {
        writeJava(sourceRoot.resolve("org/springframework/stereotype/Service.java"), """
                package org.springframework.stereotype;
                public @interface Service {}
                """);
        writeJava(sourceRoot.resolve("org/springframework/web/bind/annotation/RestController.java"), """
                package org.springframework.web.bind.annotation;
                public @interface RestController {}
                """);
        writeJava(sourceRoot.resolve("org/springframework/web/bind/annotation/RequestMapping.java"), """
                package org.springframework.web.bind.annotation;
                public @interface RequestMapping { String[] value() default {}; }
                """);
        writeJava(sourceRoot.resolve("org/springframework/web/bind/annotation/PostMapping.java"), """
                package org.springframework.web.bind.annotation;
                public @interface PostMapping { String[] value() default {}; }
                """);
        writeJava(sourceRoot.resolve("org/springframework/jdbc/core/JdbcTemplate.java"), """
                package org.springframework.jdbc.core;
                public class JdbcTemplate { public int update(String sql) { return 0; } }
                """);
    }

    private static void writeFilterFixtureTypes(Path sourceRoot) throws Exception {
        writeJava(sourceRoot.resolve("org/springframework/stereotype/Component.java"),
                "package org.springframework.stereotype; public @interface Component {}");
        writeJava(sourceRoot.resolve("org/springframework/stereotype/Service.java"),
                "package org.springframework.stereotype; public @interface Service {}");
        writeJava(sourceRoot.resolve("org/springframework/beans/factory/annotation/Autowired.java"),
                "package org.springframework.beans.factory.annotation; public @interface Autowired {}");
        writeJava(sourceRoot.resolve("org/springframework/jdbc/core/JdbcTemplate.java"),
                "package org.springframework.jdbc.core; public class JdbcTemplate { public int update(String sql) { return 0; } }");
        writeJava(sourceRoot.resolve("jakarta/servlet/annotation/WebFilter.java"), """
                package jakarta.servlet.annotation;
                public @interface WebFilter { String[] value() default {}; }
                """);
        writeJava(sourceRoot.resolve("jakarta/servlet/Filter.java"), """
                package jakarta.servlet;
                public interface Filter {
                    void doFilter(ServletRequest request, ServletResponse response, FilterChain chain);
                }
                """);
        writeJava(sourceRoot.resolve("jakarta/servlet/ServletRequest.java"),
                "package jakarta.servlet; public interface ServletRequest {}");
        writeJava(sourceRoot.resolve("jakarta/servlet/ServletResponse.java"),
                "package jakarta.servlet; public interface ServletResponse {}");
        writeJava(sourceRoot.resolve("jakarta/servlet/FilterChain.java"),
                "package jakarta.servlet; public interface FilterChain {}");
    }

    private static void writeScheduledFixtureTypes(Path sourceRoot) throws Exception {
        writeJava(sourceRoot.resolve("org/springframework/stereotype/Component.java"),
                "package org.springframework.stereotype; public @interface Component { String value() default \"\"; }");
        writeJava(sourceRoot.resolve("org/springframework/stereotype/Service.java"),
                "package org.springframework.stereotype; public @interface Service { String value() default \"\"; }");
        writeJava(sourceRoot.resolve("org/springframework/scheduling/annotation/EnableScheduling.java"),
                "package org.springframework.scheduling.annotation; public @interface EnableScheduling {}");
        writeJava(sourceRoot.resolve("org/springframework/scheduling/annotation/Scheduled.java"), """
                package org.springframework.scheduling.annotation;
                public @interface Scheduled { String fixedDelay() default ""; }
                """);
        writeJava(sourceRoot.resolve("jakarta/annotation/Resource.java"), """
                package jakarta.annotation;
                public @interface Resource { String name() default ""; }
                """);
        writeJava(sourceRoot.resolve("org/springframework/jdbc/core/JdbcTemplate.java"),
                "package org.springframework.jdbc.core; public class JdbcTemplate { public int update(String sql) { return 0; } }");
    }

    private static void writeStartupFixtureTypes(Path sourceRoot) throws Exception {
        writeJava(sourceRoot.resolve("org/springframework/stereotype/Component.java"),
                "package org.springframework.stereotype; public @interface Component { String value() default \"\"; }");
        writeJava(sourceRoot.resolve("org/springframework/boot/ApplicationArguments.java"),
                "package org.springframework.boot; public interface ApplicationArguments {}");
        writeJava(sourceRoot.resolve("org/springframework/boot/ApplicationRunner.java"), """
                package org.springframework.boot;
                public interface ApplicationRunner { void run(ApplicationArguments args); }
                """);
        writeJava(sourceRoot.resolve("org/springframework/boot/CommandLineRunner.java"), """
                package org.springframework.boot;
                public interface CommandLineRunner { void run(String... args); }
                """);
        writeJava(sourceRoot.resolve("org/springframework/jdbc/core/JdbcTemplate.java"),
                "package org.springframework.jdbc.core; public class JdbcTemplate { public int update(String sql) { return 0; } }");
    }

    private static void writeEventFixtureTypes(Path sourceRoot) throws Exception {
        writeJava(sourceRoot.resolve("org/springframework/stereotype/Component.java"),
                "package org.springframework.stereotype; public @interface Component { String value() default \"\"; }");
        writeJava(sourceRoot.resolve("org/springframework/context/event/EventListener.java"), """
                package org.springframework.context.event;
                public @interface EventListener { Class<?>[] classes() default {}; }
                """);
        writeJava(sourceRoot.resolve("org/springframework/jdbc/core/JdbcTemplate.java"),
                "package org.springframework.jdbc.core; public class JdbcTemplate { public int update(String sql) { return 0; } }");
    }

    private static boolean hasEvidence(
            List<io.graphcrud.model.CanonicalFact> facts,
            RelationshipAssertion assertion,
            io.graphcrud.model.EvidenceLevel level) {
        return facts.stream().filter(EvidenceOccurrence.class::isInstance).map(EvidenceOccurrence.class::cast)
                .anyMatch(evidence -> evidence.subject().equals(assertion.id()) && evidence.evidenceLevel() == level);
    }

    private static String canonicalHash(List<io.graphcrud.model.CanonicalFact> facts) throws Exception {
        var canonical = facts.stream()
                .map(fact -> fact.factKind() + "|" + fact.canonicalId())
                .collect(java.util.stream.Collectors.joining("\n"));
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static void writeJava(Path path, String source) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, source, StandardCharsets.UTF_8);
    }
}
