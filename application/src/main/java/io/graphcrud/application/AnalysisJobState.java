package io.graphcrud.application;

public enum AnalysisJobState {
    QUEUED,
    RUNNING,
    SEALING,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CANCELLED,
    RECONCILIATION_REQUIRED
}
