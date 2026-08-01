package io.graphcrud.application;

import java.util.Set;

public record GraphStoreCapabilities(Set<GraphStoreCapability> values) {
    private static final Set<GraphStoreCapability> STAGE_ONE_REQUIRED = Set.of(
            GraphStoreCapability.BATCH_WRITE,
            GraphStoreCapability.STABLE_LOOKUP,
            GraphStoreCapability.BOUNDED_TRAVERSAL,
            GraphStoreCapability.SNAPSHOT_SWITCHING,
            GraphStoreCapability.TRANSACTIONAL_PROMOTION);

    public GraphStoreCapabilities {
        values = Set.copyOf(values);
    }

    public static GraphStoreCapabilities stageOneReference() {
        return new GraphStoreCapabilities(STAGE_ONE_REQUIRED);
    }

    public void requireStageOne() {
        var missing = new java.util.HashSet<>(STAGE_ONE_REQUIRED);
        missing.removeAll(values);
        if (!missing.isEmpty()) {
            throw new UnsupportedOperationException("GraphStore is missing required capabilities: " + missing);
        }
    }
}
