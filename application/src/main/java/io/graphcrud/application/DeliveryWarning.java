package io.graphcrud.application;

public record DeliveryWarning(String code, String message) {
    public DeliveryWarning { if (code == null || code.isBlank() || message == null || message.isBlank()) throw new IllegalArgumentException(); }
}
