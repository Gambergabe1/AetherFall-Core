package gg.aetherfall.core.data;

import java.util.LinkedHashMap;
import java.util.Map;

/** Legacy comma-separated progress supports namespaced IDs containing colons. */
public final class BestiaryProgressCodec {
    private BestiaryProgressCodec() { }

    public static Map<String, Integer> decode(String raw) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return result;
        for (String entry : raw.split(",")) {
            if (result.size() >= 4096) break;
            int delimiter = entry.lastIndexOf(':');
            if (delimiter <= 0) continue;
            String key = entry.substring(0, delimiter);
            if (!key.matches("[a-z0-9_]+(?::[a-z0-9_]+)?")) continue;
            try {
                int count = Integer.parseInt(entry.substring(delimiter + 1));
                if (count >= 0) result.merge(key, Math.min(1_000_000, count), Math::max);
            } catch (NumberFormatException ignored) { }
        }
        return result;
    }
}
