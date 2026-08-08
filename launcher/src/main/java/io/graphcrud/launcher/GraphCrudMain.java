package io.graphcrud.launcher;

import io.graphcrud.application.*;
import io.graphcrud.infrastructure.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;

/** Native JVM composition root. All customer analysis remains local and source roots are read-only. */
public final class GraphCrudMain {
    private GraphCrudMain() {}

    public static void main(String[] args) throws Exception {
        var uri = environment("GRAPHCRUD_BOLT_URI", "bolt://localhost:7687");
        var user = environment("GRAPHCRUD_BOLT_USER", "neo4j");
        var password = requiredEnvironment("GRAPHCRUD_BOLT_PASSWORD");
        try (var driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password))) {
            driver.verifyConnectivity();
            var store = new Neo4jGraphStore(driver);
            var operations = new DefaultDeliveryOperations(store, new DefaultAnalysisJobRunner(store,
                    millis -> System.err.println("{\"event\":\"graph-write-stage-timing\",\"graphWriteMs\":"
                            + millis + "}")));
            var cli = new GraphCrudCli(operations, GraphCrudMain::analysisRequest);
            var result = cli.execute(args);
            System.out.print(result.stdout());
            System.err.print(result.stderr());
            if (result.exitCode() != 0) System.exit(result.exitCode());
        }
    }

    private static AnalysisJobRequest analysisRequest(ProjectId project, io.graphcrud.model.SnapshotId snapshot,
                                                       String[] args) {
        var root = Path.of(args.length > 3 ? args[3] : requiredEnvironment("GRAPHCRUD_SOURCE_ROOT"))
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("analysis root is not a directory: " + root);
        return new AnalysisJobRequest(project, snapshot, token -> analyze(project, snapshot, root, token), 500);
    }

    private static CanonicalFactAnalysisResult analyze(ProjectId project, io.graphcrud.model.SnapshotId snapshot,
                                                        Path root, CancellationToken token) {
        var discoveryStarted = System.nanoTime();
        requireNotCancelled(token);
        var java = new EclipseJdtJavaSourceAnalyzer();
        var sources = findJavaSourceRoots(root);
        var schemas = findSchemaSources(root);
        var discoveryElapsed = elapsedMillis(discoveryStarted);
        var parsingStarted = System.nanoTime();
        var javaResult = java.analyze(new JavaAnalysisInput(project, snapshot, root,
                new JavaBuildMetadata(root.getFileName().toString(), sources, List.of(), true,
                        Optional.of(new PostgreSqlContext("default", "public")))));
        var parsingElapsed = elapsedMillis(parsingStarted);
        var resolutionStarted = System.nanoTime();
        requireNotCancelled(token);
        var persistence = new MyBatisPostgreSqlProjectAnalyzer().analyze(new PersistenceProjectAnalysisInput(
                project, snapshot, root, root.getFileName().toString(), "default", "public", Optional.empty(),
                "default", schemas, javaResult.facts()));
        var completion = javaResult.completion() == SnapshotCompletion.COMPLETE
                && persistence.completion() == SnapshotCompletion.COMPLETE
                ? SnapshotCompletion.COMPLETE : SnapshotCompletion.PARTIAL;
        System.err.println(new StageTimingReport(discoveryElapsed, parsingElapsed,
                elapsedMillis(resolutionStarted)).toJson());
        return new CanonicalFactAnalysisResult(persistence.facts(), completion);
    }

    private static Map<Path, java.nio.charset.Charset> findJavaSourceRoots(Path root) {
        try (var paths = Files.walk(root)) {
            var roots = paths.filter(Files::isDirectory)
                    .filter(path -> path.endsWith(Path.of("src", "main", "java")))
                    .collect(java.util.stream.Collectors.toMap(Path::toAbsolutePath, ignored -> StandardCharsets.UTF_8));
            return roots.isEmpty() ? Map.of(root, StandardCharsets.UTF_8) : roots;
        } catch (java.io.IOException failure) { throw new IllegalArgumentException("cannot discover Java sources", failure); }
    }

    private static List<SchemaSource> findSchemaSources(Path root) {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".sql"))
                    .sorted().map(path -> new SchemaSource(path.toAbsolutePath(), "default", 0)).toList();
        } catch (java.io.IOException failure) { throw new IllegalArgumentException("cannot discover Schema Sources", failure); }
    }

    private static String requiredEnvironment(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing environment variable: " + name);
        return value;
    }
    private static String environment(String name, String fallback) {
        var value = System.getenv(name); return value == null || value.isBlank() ? fallback : value;
    }
    private static void requireNotCancelled(CancellationToken token) {
        if (token.isCancellationRequested()) throw new IllegalStateException("analysis cancelled");
    }

    private static long elapsedMillis(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
