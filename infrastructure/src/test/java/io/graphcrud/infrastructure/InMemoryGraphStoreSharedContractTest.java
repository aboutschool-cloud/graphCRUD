package io.graphcrud.infrastructure;

import io.graphcrud.application.GraphStore;

class InMemoryGraphStoreSharedContractTest extends GraphStoreSharedContract {
    private final GraphStore store = new InMemoryGraphStore();
    @Override protected GraphStore store() { return store; }
}
