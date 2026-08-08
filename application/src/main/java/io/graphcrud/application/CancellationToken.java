package io.graphcrud.application;

@FunctionalInterface
public interface CancellationToken {
    CancellationToken NEVER = () -> false;

    boolean isCancellationRequested();
}
