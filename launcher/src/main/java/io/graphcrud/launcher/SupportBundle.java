package io.graphcrud.launcher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

public final class SupportBundle {
    private SupportBundle() {}
    public static byte[] create(Map<String, String> diagnostics) {
        var safe = new TreeMap<String, String>();
        diagnostics.forEach((key, value) -> safe.put(safeKey(key), sha256(value)));
        var body = new StringBuilder("{\"schemaVersion\":\"v1\",\"redacted\":true,\"sha256\":{");
        var first = true;
        for (var entry : safe.entrySet()) {
            if (!first) body.append(','); first = false;
            body.append('\"').append(DeliveryJson.escape(entry.getKey())).append("\":\"").append(entry.getValue()).append('\"');
        }
        return body.append("}}\n").toString().getBytes(StandardCharsets.UTF_8);
    }
    private static String safeKey(String key) { return key.matches("[A-Za-z0-9_.-]{1,40}") ? key : "field"; }
    private static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
