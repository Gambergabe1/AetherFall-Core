package gg.aetherfall.core.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** MiniMessage helpers. Placeholders are written as {key} in config and replaced before parsing. */
public final class Text {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {}

    public static Component mm(String raw) {
        return MM.deserialize(raw == null ? "" : raw);
    }

    public static Component mm(String raw, Map<String, ?> vars) {
        return mm(fill(raw, vars));
    }

    /** Parses and strips the default italic that item names/lore get. */
    public static Component item(String raw, Map<String, ?> vars) {
        return mm(raw, vars).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> lore(List<String> lines, Map<String, ?> vars) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) out.add(item(line, vars));
        return out;
    }

    public static String fill(String raw, Map<String, ?> vars) {
        if (raw == null) return "";
        if (vars == null || vars.isEmpty()) return raw;
        String s = raw;
        for (Map.Entry<String, ?> e : vars.entrySet()) {
            // Escape player-controlled values so names can't inject MiniMessage tags.
            s = s.replace("{" + e.getKey() + "}", MM.escapeTags(String.valueOf(e.getValue())));
        }
        return s;
    }

    public static TagResolver name(String key, Component value) {
        return Placeholder.component(key, value);
    }

    /** 3725 seconds -> "1h 2m". */
    public static String duration(long seconds) {
        long d = seconds / 86400, h = (seconds % 86400) / 3600, m = (seconds % 3600) / 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }

    public static String number(long n) {
        return String.format("%,d", n);
    }

    public static String progressBar(double ratio, int width) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, ratio)) * width);
        return "<green>" + "|".repeat(filled) + "<dark_gray>" + "|".repeat(width - filled);
    }
}
