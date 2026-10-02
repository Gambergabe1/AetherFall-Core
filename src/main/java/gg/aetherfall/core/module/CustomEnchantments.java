package gg.aetherfall.core.module;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.LinkedHashMap;
import java.util.Map;

/** Persistent custom-enchant storage. Definitions and gameplay effects can grow independently. */
public final class CustomEnchantments {
    private final NamespacedKey key;

    public CustomEnchantments(org.bukkit.plugin.Plugin plugin) { key = new NamespacedKey(plugin, "custom_enchants"); }

    public Map<String, Integer> levels(ItemStack stack) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (stack == null || !stack.hasItemMeta()) return result;
        String raw = stack.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) return result;
        for (String entry : raw.split(";")) {
            String[] parts = entry.split(":", 2);
            if (parts.length == 2) try {
                String id = parts[0].toLowerCase(java.util.Locale.ROOT);
                int level = Integer.parseInt(parts[1]);
                if (id.matches("[a-z0-9_]{1,48}") && level >= 1 && level <= 50) result.put(id, level);
            } catch (NumberFormatException ignored) { }
        }
        return result;
    }

    public void apply(ItemStack stack, String id, int level) {
        if (stack == null || id == null || !id.toLowerCase(java.util.Locale.ROOT).matches("[a-z0-9_]{1,48}") || level <= 0) return;
        Map<String, Integer> levels = levels(stack);
        levels.put(id.toLowerCase(java.util.Locale.ROOT), Math.min(50, level));
        write(stack, levels);
    }

    public void remove(ItemStack stack, String id) {
        Map<String, Integer> levels = levels(stack);
        levels.remove(id.toLowerCase(java.util.Locale.ROOT));
        write(stack, levels);
    }

    /** Replaces the server-side enchant payload after an administrative validation pass. */
    public void replace(ItemStack stack, Map<String, Integer> levels) {
        if (stack == null || !stack.hasItemMeta()) return;
        Map<String, Integer> clean = new LinkedHashMap<>();
        if (levels != null) for (Map.Entry<String, Integer> entry : levels.entrySet()) {
            String id = entry.getKey() == null ? "" : entry.getKey().toLowerCase(java.util.Locale.ROOT);
            Integer level = entry.getValue();
            if (id.matches("[a-z0-9_]{1,48}") && level != null && level >= 1 && level <= 50) clean.put(id, level);
        }
        write(stack, clean);
    }

    private void write(ItemStack stack, Map<String, Integer> levels) {
        var meta = stack.getItemMeta();
        if (levels.isEmpty()) meta.getPersistentDataContainer().remove(key);
        else meta.getPersistentDataContainer().set(key, PersistentDataType.STRING,
                levels.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).collect(java.util.stream.Collectors.joining(";")));
        stack.setItemMeta(meta);
    }
}
