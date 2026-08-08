package io.graphcrud.launcher;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class Stage6PackageContractTest {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test
    void corpus_manifest_is_immutable_and_never_claims_accuracy_truth() throws Exception {
        var manifest = Files.readString(ROOT.resolve("release/corpora.json"));
        assertTrue(manifest.contains("5f1d7e3e01054a663cd1ae8b37141fe88c015f58"));
        assertTrue(manifest.contains("ce8015be2c2658d0b657be20328e53045f3a9641"));
        assertTrue(manifest.contains("6bfdcae06eaf218c4204382de277499be6c88c1b"));
        assertTrue(manifest.contains("db0b3c4bb58a38bad9c3d78b7269d8a477cc6a63"));
        assertEquals(4, manifest.split("\"accuracyOracle\": false", -1).length - 1);
        assertFalse(manifest.contains("\"ref\": \"master\""));
        assertFalse(manifest.contains("\"ref\": \"5.X\""));
    }

    @Test
    void compose_keeps_neo4j_replaceable_digest_pinned_private_and_offline() throws Exception {
        var compose = Files.readString(ROOT.resolve("release/compose.yaml"));
        assertTrue(compose.contains("graphcrud/neo4j-community:5.26.29-sanitized@sha256:61715f2b21db10790eaa0cad61470a5c44001907fe2c86ff6deec5997db51edf"));
        assertTrue(compose.contains("pull_policy: never"));
        assertTrue(compose.contains("NEO4J_dbms_usage__report_enabled: \"false\""));
        assertTrue(compose.contains("GRAPHCRUD_BOLT_URI"));
        assertTrue(compose.contains(":/workspace:ro"));
        assertFalse(compose.contains("latest"));
        assertFalse(compose.contains("enterprise"));
        assertTrue(Pattern.compile("image: graphcrud:0\\.1\\.0@sha256:[0-9a-f]{64}").matcher(compose).find());
        assertTrue(compose.contains("profiles: [\"cli\"]"));
        assertTrue(compose.contains("mem_limit:"));
        assertTrue(compose.contains("cpus:"));
        var dockerfile = Files.readString(ROOT.resolve("release/Dockerfile"));
        assertTrue(dockerfile.contains("eclipse-temurin:21-jre-ubi9-minimal@sha256:3c624d2b86ddcf884f2ea01355da5c0eefaeb93904c61bc0d3a775b176f04947"));
        assertTrue(dockerfile.contains("USER 65532:65532"));
        assertFalse(dockerfile.contains("gradlew"));
        var neo4jDockerfile = Files.readString(ROOT.resolve("release/Dockerfile.neo4j-community"));
        assertTrue(neo4jDockerfile.contains("FROM scratch"));
        assertTrue(neo4jDockerfile.contains("ADD build/stage6/neo4j-rootfs/normalized.tar /"));
        var rootfsScript = Files.readString(ROOT.resolve("release/prepare-neo4j-rootfs.ps1"));
        assertTrue(rootfsScript.contains("rm /var/lib/neo4j/products/neo4j-fleet-management-plugin-1.2.0-v5.jar"));
        var external = Files.readString(ROOT.resolve("release/compose-external.yaml"));
        assertTrue(external.contains("GRAPHCRUD_BOLT_URI: ${GRAPHCRUD_BOLT_URI:?"));
        assertTrue(external.contains("graphcrud:0.1.0@sha256:f9040ea692b1d0f3896d02aa04729fa4693d7627aba8816a2696bc54bfe0f604"));
        assertFalse(external.contains("neo4j:"));
        assertFalse(external.contains("depends_on:"));
    }

    @Test
    void run_metadata_is_deterministic_and_performance_gate_is_comparable_only() {
        var metadata = new CorpusRunMetadata("mybatis-spring-control",
                "5f1d7e3e01054a663cd1ae8b37141fe88c015f58", "graphcrud-0.1.0", "Temurin 21",
                "linux-x86_64-8cpu-16gb", "graphcrud analyze corpus", Instant.parse("2026-08-02T00:00:00Z"),
                12_000, "facts-sha256", "COMPLETE", Map.of("java", 4_000L, "sql", 8_000L));
        assertEquals(metadata.toJson(), metadata.toJson());
        assertTrue(metadata.toJson().contains("\"accuracyOracle\":false"));
        assertEquals(PerformanceGate.Outcome.PASS,
                PerformanceGate.compare("fixed", "fixed", Map.of("sql", 100L), Map.of("sql", 120L)).outcome());
        assertEquals(PerformanceGate.Outcome.REGRESSION,
                PerformanceGate.compare("fixed", "fixed", Map.of("sql", 100L), Map.of("sql", 121L)).outcome());
        assertEquals(PerformanceGate.Outcome.INCOMPARABLE,
                PerformanceGate.compare("fixed", "other", Map.of("sql", 100L), Map.of("sql", 500L)).outcome());
        assertEquals(PerformanceGate.Outcome.REGRESSION,
                PerformanceGate.compare("fixed", "fixed", Map.of("sql", 100L), Map.of()).outcome());
        assertEquals(PerformanceGate.Outcome.REGRESSION,
                PerformanceGate.compare("fixed", "fixed", Map.of("sql", 100L), Map.of("sql", 100L, "extra", 1L)).outcome());
        assertEquals(3, ReleasePolicyMain.execute(new String[]{"performance", "fixed", "other",
                "sql=100", "sql=100"}));
    }

    @Test
    void security_gate_requires_fresh_databases_and_current_explicit_exceptions() {
        var now = Instant.parse("2026-08-02T08:00:00Z");
        var valid = new SecurityPolicy.ExceptionRecord("CVE-2026-11352", "release-owner",
                "No vendor fix; affected network feature is not used by GraphCRUD", Instant.parse("2026-08-16T00:00:00Z"));
        assertEquals(SecurityPolicy.Outcome.PASS,
                SecurityPolicy.evaluate(now, Instant.parse("2026-08-02T00:55:42Z"),
                        List.of("CVE-2026-11352"), List.of(valid)).outcome());
        assertEquals(SecurityPolicy.Outcome.FAIL,
                SecurityPolicy.evaluate(now, Instant.parse("2026-08-02T00:55:42Z"),
                        List.of("CVE-2026-11586"), List.of(valid)).outcome());
        assertEquals(SecurityPolicy.Outcome.FAIL,
                SecurityPolicy.evaluate(now, Instant.parse("2026-07-29T00:00:00Z"), List.of(), List.of()).outcome());
        var expired = new SecurityPolicy.ExceptionRecord("CVE-2026-11352", "release-owner", "awaiting vendor fix",
                Instant.parse("2026-08-01T00:00:00Z"));
        assertEquals(SecurityPolicy.Outcome.FAIL,
                SecurityPolicy.evaluate(now, Instant.parse("2026-08-02T00:55:42Z"),
                        List.of("CVE-2026-11352"), List.of(expired)).outcome());
    }

    @Test
    void stage_timing_report_is_stable_and_machine_readable() {
        var report = new StageTimingReport(12, 123, 456);
        assertEquals("{\"event\":\"analysis-stage-timing\",\"discoveryMs\":12,\"parsingMs\":123,\"resolutionMs\":456}", report.toJson());
    }
}
