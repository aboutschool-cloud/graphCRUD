package io.graphcrud.launcher;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/** Command bridge so release scripts and unit tests execute the same policy code. */
public final class ReleasePolicyMain {
    private ReleasePolicyMain() {}

    public static void main(String[] args) {
        var exit = execute(args);
        if (exit != 0) System.exit(exit);
    }

    static int execute(String[] args) {
        try {
            var success = switch (args[0]) {
                case "performance" -> performance(args);
                case "security-db" -> securityDatabase(args);
                case "security-exception" -> securityException(args);
                default -> throw new IllegalArgumentException("unknown policy: " + args[0]);
            };
            return success ? 0 : 3;
        } catch (RuntimeException failure) {
            System.err.println(failure.getMessage());
            return 2;
        }
    }

    private static boolean performance(String[] args) {
        if (args.length != 5) throw new IllegalArgumentException("performance requires environments and two stage maps");
        var result = PerformanceGate.compare(args[1], args[2], stages(args[3]), stages(args[4]));
        System.out.println(result.outcome() + ":" + String.join(",", result.regressedStages()));
        return result.outcome() == PerformanceGate.Outcome.PASS;
    }

    private static boolean securityDatabase(String[] args) {
        if (args.length != 3) throw new IllegalArgumentException("security-db requires evaluatedAt and updatedAt");
        var result = SecurityPolicy.evaluate(Instant.parse(args[1]), Instant.parse(args[2]), List.of(), List.of());
        result.reasons().forEach(System.out::println);
        return result.outcome() == SecurityPolicy.Outcome.PASS;
    }

    private static boolean securityException(String[] args) {
        if (args.length != 6) throw new IllegalArgumentException("security-exception requires evaluatedAt, id, owner, reason, expiresAt");
        var now = Instant.parse(args[1]);
        var exception = new SecurityPolicy.ExceptionRecord(args[2], args[3], args[4], Instant.parse(args[5]));
        var result = SecurityPolicy.evaluate(now, now, List.of(args[2]), List.of(exception));
        result.reasons().forEach(System.out::println);
        return result.outcome() == SecurityPolicy.Outcome.PASS;
    }

    private static java.util.Map<String, Long> stages(String encoded) {
        var result = new LinkedHashMap<String, Long>();
        if (encoded.isBlank() || encoded.equals("-")) return result;
        Arrays.stream(encoded.split(",")).forEach(entry -> {
            var parts = entry.split("=", 2);
            if (parts.length != 2 || result.put(parts[0], Long.parseLong(parts[1])) != null) {
                throw new IllegalArgumentException("invalid or duplicate stage timing: " + entry);
            }
        });
        return result;
    }
}
