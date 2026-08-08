package io.graphcrud.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class QueryBoundsContractTest {
    @Test
    void default_table_impact_bounds_are_shared_by_every_caller() {
        assertEquals(new QueryBounds(12, 100), QueryBounds.defaults());
    }
}
