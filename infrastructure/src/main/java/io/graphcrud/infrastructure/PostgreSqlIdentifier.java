package io.graphcrud.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

record PostgreSqlIdentifier(String schema, String name, String original) {
    static PostgreSqlIdentifier parse(String raw, String defaultSchema) {
        var split = splitQualified(raw);
        return new PostgreSqlIdentifier(
                normalize(split.size() == 2 ? split.get(0) : defaultSchema),
                normalize(split.get(split.size() - 1)), raw);
    }

    String qualifiedName() { return schema + "." + name; }

    private static List<String> splitQualified(String raw) {
        var values = new ArrayList<String>();
        var current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < raw.length() && raw.charAt(index + 1) == '"') {
                    current.append("\"\""); index++; continue;
                }
                quoted = !quoted;
            }
            if (character == '.' && !quoted) {
                values.add(current.toString()); current.setLength(0);
            } else current.append(character);
        }
        values.add(current.toString());
        if (values.size() > 2) throw new IllegalArgumentException("unsupported qualified identifier: " + raw);
        return values;
    }

    private static String normalize(String value) {
        var trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
