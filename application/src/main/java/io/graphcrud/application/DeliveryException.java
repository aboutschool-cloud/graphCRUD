package io.graphcrud.application;

public final class DeliveryException extends RuntimeException {
    private final DeliveryErrorCode code;
    public DeliveryException(DeliveryErrorCode code, String message) { super(message); this.code = code; }
    public DeliveryErrorCode code() { return code; }
}
