package gg.aetherfall.core.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;

public final class ItemBuilder {
    private final ItemStack stack;
    private final ItemMeta meta;

    public ItemBuilder(Material material) {
        this(material, 1);
    }

    public ItemBuilder(Material material, int amount) {
        this.stack = new ItemStack(material, Math.max(1, Math.min(amount, material.getMaxStackSize())));
        this.meta = stack.getItemMeta();
    }

    public ItemBuilder name(String raw) {
        return name(raw, Map.of());
    }

    public ItemBuilder name(String raw, Map<String, ?> vars) {
        meta.displayName(Text.item(raw, vars));
        return this;
    }

    public ItemBuilder lore(List<String> lines) {
        return lore(lines, Map.of());
    }

    public ItemBuilder lore(List<String> lines, Map<String, ?> vars) {
        meta.lore(Text.lore(lines, vars));
        return this;
    }

    public ItemBuilder glow(boolean glow) {
        if (glow) meta.setEnchantmentGlintOverride(true);
        return this;
    }

    public ItemStack build() {
        meta.addItemFlags(ItemFlag.values());
        stack.setItemMeta(meta);
        return stack;
    }

    public static Material material(String name, Material fallback) {
        if (name == null) return fallback;
        Material m = Material.matchMaterial(name);
        return m == null || !m.isItem() ? fallback : m;
    }
}
