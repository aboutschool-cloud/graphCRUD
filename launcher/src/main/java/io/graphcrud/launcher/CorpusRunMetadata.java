package io.graphcrud.launcher;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Reproducible evidence for one compatibility corpus execution; never an accuracy oracle. */
public record CorpusRunMetadata(String corpusRole, String corpusCommit, String analyzerVersion,
                                String jdk, String environmentId, String command, Instant startedAt,
                                long durationMillis, String resultHash, String completion,
                                Map<String, Long> stageMillis) {
    public CorpusRunMetadata { stageMillis = Map.copyOf(stageMillis); }
    public String toJson() {
        var stages = new TreeMap<>(stageMillis).entrySet().stream()
                .map(e -> "\"" + DeliveryJson.escape(e.getKey()) + "\":" + e.getValue())
                .collect(Collectors.joining(",", "{", "}"));
        return "{\"schemaVersion\":\"1\",\"accuracyOracle\":false,\"corpusRole\":\"" + DeliveryJson.escape(corpusRole)
                + "\",\"corpusCommit\":\"" + corpusCommit + "\",\"analyzerVersion\":\"" + DeliveryJson.escape(analyzerVersion)
                + "\",\"jdk\":\"" + DeliveryJson.escape(jdk) + "\",\"environmentId\":\"" + DeliveryJson.escape(environmentId)
                + "\",\"command\":\"" + DeliveryJson.escape(command) + "\",\"startedAt\":\"" + startedAt
                + "\",\"durationMillis\":" + durationMillis + ",\"resultHash\":\"" + resultHash
                + "\",\"completion\":\"" + completion + "\",\"stageMillis\":" + stages + "}";
    }
}
