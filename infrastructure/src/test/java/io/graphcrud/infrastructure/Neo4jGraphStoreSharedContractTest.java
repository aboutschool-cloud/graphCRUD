package io.graphcrud.infrastructure;

import io.graphcrud.application.GraphStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;

@Tag("neo4j")
class Neo4jGraphStoreSharedContractTest extends GraphStoreSharedContract {
    private Neo4j server;
    private org.neo4j.driver.Driver driver;
    private GraphStore store;

    @BeforeEach
    void startNeo4j() {
        server = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
        driver = GraphDatabase.driver(server.boltURI(), AuthTokens.none());
        store = new Neo4jGraphStore(driver);
    }

    @AfterEach
    void stopNeo4j() {
        driver.close();
        server.close();
    }

    @Override protected GraphStore store() { return store; }
}
