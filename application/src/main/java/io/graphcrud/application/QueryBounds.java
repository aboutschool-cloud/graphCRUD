package io.graphcrud.application;

public record QueryBounds(int maximumDepth, int maximumPaths) {
    private static final QueryBounds DEFAULTS = new QueryBounds(12, 100);

    public QueryBounds {
        if (maximumDepth < 1 || maximumPaths < 1) {
            throw new IllegalArgumentException("query bounds must be positive");
        }
    }

    public static QueryBounds defaults() {
        return DEFAULTS;
    }
}
