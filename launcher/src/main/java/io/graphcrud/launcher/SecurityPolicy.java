package io.graphcrud.launcher;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Deterministic Stage 6 release gate for vulnerability database freshness and exceptions. */
public final class SecurityPolicy {
    private static final Duration MAXIMUM_DATABASE_AGE = Duration.ofHours(72);

    private SecurityPolicy() {}

    public enum Outcome { PASS, FAIL }

    public record ExceptionRecord(String id, String owner, String reason, Instant expiresAt) {}

    public record Result(Outcome outcome, List<String> reasons) {}

    public static Result evaluate(Instant evaluatedAt, Instant databaseUpdatedAt,
                                  List<String> highOrCriticalIds, List<ExceptionRecord> exceptions) {
        var reasons = new ArrayList<String>();
        if (databaseUpdatedAt.isAfter(evaluatedAt)
                || Duration.between(databaseUpdatedAt, evaluatedAt).compareTo(MAXIMUM_DATABASE_AGE) > 0) {
            reasons.add("vulnerability database is not current within 72 hours");
        }
        var byId = new HashMap<String, ExceptionRecord>();
        exceptions.forEach(exception -> byId.put(exception.id(), exception));
        highOrCriticalIds.stream().distinct().sorted().forEach(id -> {
            var exception = byId.get(id);
            if (exception == null || exception.owner().isBlank() || exception.reason().isBlank()
                    || !exception.expiresAt().isAfter(evaluatedAt)) {
                reasons.add("missing or expired exception: " + id);
            }
        });
        return new Result(reasons.isEmpty() ? Outcome.PASS : Outcome.FAIL, List.copyOf(reasons));
    }
}
