package io.graphcrud.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;

class GraphStoreCapabilitiesContractTest {
    @Test
    void incomplete_graph_stores_are_rejected_before_use() {
        var complete = GraphStoreCapabilities.stageOneReference();
        var incomplete = new GraphStoreCapabilities(Set.of(GraphStoreCapability.BATCH_WRITE));

        assertDoesNotThrow(complete::requireStageOne);
        assertThrows(UnsupportedOperationException.class, incomplete::requireStageOne);
    }
}
