package io.graphcrud.infrastructure;

import io.graphcrud.application.GraphStore;
import io.graphcrud.application.GraphStoreCapabilities;
import io.graphcrud.application.ProjectId;
import io.graphcrud.application.QueryBounds;
import io.graphcrud.application.SnapshotCompletion;
import io.graphcrud.application.SnapshotLease;
import io.graphcrud.application.TableImpactResult;
import io.graphcrud.model.CanonicalFact;
import io.graphcrud.model.NodeId;
import io.graphcrud.model.SnapshotId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Values;

/** Neo4j Community-compatible GraphStore adapter. The supplied Driver remains caller-owned. */
public final class Neo4jGraphStore implements GraphStore {
    private static final GraphStoreCapabilities CAPABILITIES = GraphStoreCapabilities.stageOneReference();
    private final Driver driver;

    public Neo4jGraphStore(Driver driver) {
        this.driver = driver;
        initializeSchema();
    }

    @Override public GraphStoreCapabilities capabilities() { return CAPABILITIES; }

    private void initializeSchema() {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("CREATE CONSTRAINT graphcrud_snapshot_id IF NOT EXISTS FOR (s:GraphCrudSnapshot) REQUIRE s.id IS UNIQUE").consume();
                tx.run("CREATE CONSTRAINT graphcrud_fact_storage_id IF NOT EXISTS FOR (f:GraphCrudFact) REQUIRE f.storageId IS UNIQUE").consume();
                tx.run("CREATE CONSTRAINT graphcrud_project_id IF NOT EXISTS FOR (p:GraphCrudProject) REQUIRE p.id IS UNIQUE").consume();
                return null;
            });
            var installed = session.executeRead(tx -> tx.run(
                    "SHOW CONSTRAINTS YIELD name, labelsOrTypes, properties "
                            + "WHERE name STARTS WITH 'graphcrud_' RETURN name, labelsOrTypes, properties")
                    .list(record -> record.get("name").asString() + ":"
                            + record.get("labelsOrTypes").asList() + ":" + record.get("properties").asList()));
            var expected = List.of(
                    "graphcrud_snapshot_id:[GraphCrudSnapshot]:[id]",
                    "graphcrud_fact_storage_id:[GraphCrudFact]:[storageId]",
                    "graphcrud_project_id:[GraphCrudProject]:[id]");
            if (!installed.containsAll(expected)) {
                throw new UnsupportedOperationException("Neo4j GraphStore constraints do not match: " + installed);
            }
        }
    }

    @Override
    public void beginSnapshot(ProjectId projectId, SnapshotId snapshotId) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("CREATE (:GraphCrudSnapshot {id:$id, projectId:$project, state:'STAGING', leases:0})",
                        Values.parameters("id", snapshotId.value(), "project", projectId.value())).consume();
                return null;
            });
        }
    }

    @Override
    public void writeFacts(SnapshotId snapshotId, Collection<? extends CanonicalFact> facts) {
        var rows = facts.stream().map(fact -> Map.of(
                "storageId", snapshotId.value() + "|" + fact.factKind() + "|" + fact.canonicalId(),
                "factKind", fact.factKind(), "canonicalId", fact.canonicalId(),
                "payload", CanonicalFactCodec.encode(fact))).toList();
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                boolean staging = tx.run("MATCH (s:GraphCrudSnapshot {id:$id}) RETURN s.state = 'STAGING' AS staging",
                        Values.parameters("id", snapshotId.value())).single().get("staging").asBoolean();
                if (!staging) throw new IllegalStateException("snapshot is not staging: " + snapshotId.value());
                tx.run("UNWIND $facts AS fact MERGE (f:GraphCrudFact {storageId:fact.storageId}) "
                                + "ON CREATE SET f.snapshotId=$snapshotId, f.factKind=fact.factKind, "
                                + "f.canonicalId=fact.canonicalId, f.payload=fact.payload",
                        Values.parameters("snapshotId", snapshotId.value(), "facts", rows)).consume();
                return null;
            });
        }
    }

    @Override
    public void sealSnapshot(SnapshotId snapshotId, SnapshotCompletion completion) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                var record = tx.run("MATCH (s:GraphCrudSnapshot {id:$id, state:'STAGING'}) "
                                + "MERGE (p:GraphCrudProject {id:s.projectId}) "
                                + "SET p.nextSealSequence=coalesce(p.nextSealSequence,0)+1, "
                                + "s.state='SEALED', s.completion=$completion, s.sealSequence=p.nextSealSequence "
                                + "FOREACH (_ IN CASE WHEN $completion='COMPLETE' THEN [1] ELSE [] END | "
                                + "SET p.activeSnapshot=s.id) RETURN s.id AS id",
                        Values.parameters("id", snapshotId.value(), "completion", completion.name())).list();
                if (record.size() != 1) throw new IllegalStateException("snapshot is not staging: " + snapshotId.value());
                return null;
            });
        }
    }

    @Override
    public void promotePartial(SnapshotId snapshotId) {
        try (var session = driver.session()) {
            int changed = session.executeWrite(tx -> tx.run(
                            "MATCH (s:GraphCrudSnapshot {id:$id, state:'SEALED', completion:'PARTIAL'}) "
                                    + "MERGE (p:GraphCrudProject {id:s.projectId}) SET p.activeSnapshot=s.id RETURN count(p) AS changed",
                            Values.parameters("id", snapshotId.value())).single().get("changed").asInt());
            if (changed != 1) throw new IllegalStateException("snapshot is not a sealed partial snapshot: " + snapshotId.value());
        }
    }

    @Override public void discardSnapshot(SnapshotId snapshotId) { deleteByState(snapshotId, StoredState.STAGING); }

    @Override
    public void deleteSnapshot(SnapshotId snapshotId) { deleteByState(snapshotId, StoredState.SEALED); }

    private void deleteByState(SnapshotId snapshotId, StoredState state) {
        try (var session = driver.session()) {
            int deleted = session.executeWrite(tx -> tx.run(
                            "MATCH (s:GraphCrudSnapshot {id:$id, state:$state}) "
                                    + "WHERE s.leases=0 AND ($protect=false OR NOT EXISTS { MATCH (p:GraphCrudProject) WHERE p.activeSnapshot=s.id }) "
                                    + "OPTIONAL MATCH (f:GraphCrudFact {snapshotId:s.id}) DELETE f, s RETURN count(s) AS deleted",
                            Values.parameters("id", snapshotId.value(), "state", state.name(), "protect", state.protectActive))
                    .single().get("deleted").asInt());
            if (deleted != 1) throw new IllegalStateException("snapshot is active, retained, or not "
                    + state.name().toLowerCase() + ": " + snapshotId.value());
        }
    }

    @Override
    public SnapshotLease retainSnapshot(SnapshotId snapshotId) {
        try (var session = driver.session()) {
            int retained = session.executeWrite(tx -> tx.run(
                            "MATCH (s:GraphCrudSnapshot {id:$id, state:'SEALED'}) SET s.leases=s.leases+1 RETURN count(s) AS retained",
                            Values.parameters("id", snapshotId.value())).single().get("retained").asInt());
            if (retained != 1) throw new IllegalStateException("snapshot is not sealed: " + snapshotId.value());
        }
        return lease(snapshotId);
    }

    @Override
    public SnapshotLease retainActiveSnapshot(ProjectId projectId) {
        SnapshotId snapshotId;
        try (var session = driver.session()) {
            var ids = session.executeWrite(tx -> tx.run(
                            "MATCH (p:GraphCrudProject {id:$id}), (s:GraphCrudSnapshot {id:p.activeSnapshot, state:'SEALED'}) "
                                    + "SET s.leases=s.leases+1 RETURN s.id AS id",
                            Values.parameters("id", projectId.value())).list(record -> record.get("id").asString()));
            if (ids.size() != 1) throw new IllegalStateException("project has no active snapshot: " + projectId.value());
            snapshotId = new SnapshotId(ids.getFirst());
        }
        return lease(snapshotId);
    }

    @Override
    public Optional<SnapshotCompletion> sealedSnapshotCompletion(SnapshotId snapshotId) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run(
                            "MATCH (s:GraphCrudSnapshot {id:$id, state:'SEALED'}) RETURN s.completion AS completion",
                            Values.parameters("id", snapshotId.value())).stream()
                    .map(record -> SnapshotCompletion.valueOf(record.get("completion").asString())).findFirst());
        }
    }

    @Override
    public int cleanupSnapshots(ProjectId projectId, int retainNewest) {
        if (retainNewest < 0) throw new IllegalArgumentException("retainNewest must not be negative");
        List<SnapshotId> expired;
        try (var session = driver.session()) {
            expired = session.executeRead(tx -> tx.run(
                            "MATCH (s:GraphCrudSnapshot {projectId:$project, state:'SEALED'}) "
                                    + "RETURN s.id AS id ORDER BY s.sealSequence DESC SKIP $retain",
                            Values.parameters("project", projectId.value(), "retain", retainNewest))
                    .list(record -> new SnapshotId(record.get("id").asString())));
        }
        int deleted = 0;
        for (var snapshotId : expired) {
            try { deleteSnapshot(snapshotId); deleted++; } catch (IllegalStateException protectedSnapshot) { /* retained */ }
        }
        return deleted;
    }

    private SnapshotLease lease(SnapshotId snapshotId) {
        return new SnapshotLease() {
            private boolean closed;
            @Override public SnapshotId snapshotId() { return snapshotId; }
            @Override public synchronized void close() {
                if (closed) return;
                try (var session = driver.session()) {
                    session.executeWrite(tx -> { tx.run(
                            "MATCH (s:GraphCrudSnapshot {id:$id}) WHERE s.leases > 0 SET s.leases=s.leases-1",
                            Values.parameters("id", snapshotId.value())).consume(); return null; });
                }
                closed = true;
            }
        };
    }

    @Override
    public Optional<SnapshotId> activeSnapshot(ProjectId projectId) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run("MATCH (p:GraphCrudProject {id:$id}) RETURN p.activeSnapshot AS id",
                            Values.parameters("id", projectId.value())).stream()
                    .map(record -> new SnapshotId(record.get("id").asString())).findFirst());
        }
    }

    @Override public byte[] exportJsonl(SnapshotId snapshotId) { return CanonicalJsonl.write(readFacts(snapshotId)); }

    @Override
    public Optional<CanonicalFact> findFact(SnapshotId snapshotId, String factKind, String canonicalId) {
        requireSealed(snapshotId);
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run(
                            "MATCH (f:GraphCrudFact {snapshotId:$snapshotId, factKind:$factKind, canonicalId:$canonicalId}) RETURN f.payload AS payload",
                            Values.parameters("snapshotId", snapshotId.value(), "factKind", factKind, "canonicalId", canonicalId))
                    .stream().map(record -> CanonicalFactCodec.decode(record.get("payload").asString())).findFirst());
        }
    }

    @Override
    public TableImpactResult tableImpact(SnapshotId snapshotId, NodeId tableId, QueryBounds bounds) {
        var completion = completion(snapshotId);
        return TableImpactQuery.execute(snapshotId, completion, readFacts(snapshotId), tableId, bounds);
    }

    private List<CanonicalFact> readFacts(SnapshotId snapshotId) {
        requireSealed(snapshotId);
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run(
                            "MATCH (f:GraphCrudFact {snapshotId:$id}) RETURN f.payload AS payload ORDER BY f.factKind, f.canonicalId",
                            Values.parameters("id", snapshotId.value())).list(
                    record -> CanonicalFactCodec.decode(record.get("payload").asString())));
        }
    }

    private SnapshotCompletion completion(SnapshotId snapshotId) {
        return sealedSnapshotCompletion(snapshotId)
                .orElseThrow(() -> new IllegalStateException("snapshot is not sealed: " + snapshotId.value()));
    }

    private void requireSealed(SnapshotId snapshotId) { completion(snapshotId); }

    private enum StoredState {
        STAGING(false), SEALED(true);
        private final boolean protectActive;
        StoredState(boolean protectActive) { this.protectActive = protectActive; }
    }
}
