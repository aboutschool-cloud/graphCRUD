package io.graphcrud.launcher;

import java.util.ArrayList;
import java.util.Map;

/** Applies the release policy only to measurements from the same fixed environment. */
public final class PerformanceGate {
    private PerformanceGate() {}
    public enum Outcome { PASS, REGRESSION, INCOMPARABLE }
    public record Result(Outcome outcome, java.util.List<String> regressedStages) {}
    public static Result compare(String baselineEnvironment, String candidateEnvironment,
                                 Map<String, Long> baseline, Map<String, Long> candidate) {
        if (!baselineEnvironment.equals(candidateEnvironment)) return new Result(Outcome.INCOMPARABLE, java.util.List.of());
        var regressed = new ArrayList<String>();
        baseline.forEach((stage, value) -> {
            if (value == null || value <= 0) throw new IllegalArgumentException("baseline timing must be positive: " + stage);
            var observed = candidate.get(stage);
            if (observed == null || observed <= 0 || observed * 100L > value * 120L) regressed.add(stage);
        });
        candidate.keySet().stream().filter(stage -> !baseline.containsKey(stage))
                .map(stage -> "unexpected:" + stage).forEach(regressed::add);
        regressed.sort(String::compareTo);
        return new Result(regressed.isEmpty() ? Outcome.PASS : Outcome.REGRESSION, java.util.List.copyOf(regressed));
    }
}
