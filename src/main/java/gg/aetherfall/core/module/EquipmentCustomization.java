package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.items.CustomItem;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Shared equipment-stat and reforge API. GUIs and reforging stations can build on this class. */
public final class EquipmentCustomization {
    public record Stats(double health, double defense, double mana, double strength, double critChance,
                        double critDamage, double speed, double fortune) {
        public Stats add(Stats o) { return new Stats(health + o.health, defense + o.defense, mana + o.mana, strength + o.strength,
                critChance + o.critChance, critDamage + o.critDamage, speed + o.speed, fortune + o.fortune); }
    }

    private final AetherCore plugin;
    private final NamespacedKey reforgeKey;
    private final Map<UUID, Set<PotionEffectType>> appliedSetEffects = new ConcurrentHashMap<>();
    private final Map<UUID, Map<PotionEffectType, Integer>> appliedSetAmplifiers = new ConcurrentHashMap<>();

    public EquipmentCustomization(AetherCore plugin) {
        this.plugin = plugin;
        this.reforgeKey = new NamespacedKey(plugin, "reforge");
    }

    public Stats equipped(Player player) {
        Stats out = new Stats(0, 0, 0, 0, 0, 0, 0, 0);
        Map<String, Integer> sets = new HashMap<>();
        for (ItemStack item : player.getInventory().getArmorContents()) out = out.add(stats(item));
        for (ItemStack item : player.getInventory().getArmorContents()) {
            CustomItem custom = plugin.items().customOf(item);
            if (custom != null && custom.set() != null && !custom.set().isBlank()) sets.merge(custom.set(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : sets.entrySet()) {
            if (entry.getValue() < 4) continue;
            var set = plugin.items().set(entry.getKey());
            if (set == null) continue;
            Map<String, Integer> effects = set.effects();
            out = out.add(new Stats(effects.getOrDefault("health", 0), effects.getOrDefault("defense", 0),
                    effects.getOrDefault("mana", 0), effects.getOrDefault("strength", 0),
                    effects.getOrDefault("crit_chance", 0), effects.getOrDefault("crit_damage", 0),
                    effects.getOrDefault("speed", 0), effects.getOrDefault("fortune", 0)));
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        CustomItem custom = plugin.items().customOf(held);
        if (custom != null && !custom.type().isArmor()) out = out.add(stats(held));
        return out;
    }

    /** Applies only configured full-set effects; short duration keeps swaps and logout state correct. */
    public void refreshSetEffects(Player player) {
        Map<String, Integer> counts = new HashMap<>();
        for (ItemStack item : player.getInventory().getArmorContents()) {
            CustomItem custom = plugin.items().customOf(item);
            if (custom != null && custom.set() != null && !custom.set().isBlank()) counts.merge(custom.set(), 1, Integer::sum);
        }
        Map<PotionEffectType, Integer> previous = appliedSetAmplifiers.getOrDefault(player.getUniqueId(), Map.of());
        for (Map.Entry<PotionEffectType, Integer> entry : previous.entrySet()) {
            PotionEffectType type = entry.getKey();
            PotionEffect current = player.getPotionEffect(type);
            if (current != null && current.getDuration() <= 60 && current.getAmplifier() == entry.getValue()) player.removePotionEffect(type);
        }
        Set<PotionEffectType> applied = ConcurrentHashMap.newKeySet();
        Map<PotionEffectType, Integer> amplifiers = new HashMap<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() < 4) continue;
            var set = plugin.items().set(entry.getKey()); if (set == null) continue;
            for (Map.Entry<String, Integer> effect : set.effects().entrySet()) {
                PotionEffectType type = potion(effect.getKey());
                if (type != null) {
                    int amplifier = Math.max(0, effect.getValue());
                    player.addPotionEffect(new PotionEffect(type, 40, amplifier, false, false, false));
                    applied.add(type); amplifiers.put(type, amplifier);
                }
            }
        }
        if (applied.isEmpty()) {
            appliedSetEffects.remove(player.getUniqueId());
            appliedSetAmplifiers.remove(player.getUniqueId());
        } else {
            appliedSetEffects.put(player.getUniqueId(), applied);
            appliedSetAmplifiers.put(player.getUniqueId(), amplifiers);
        }
    }

    public void clearSetEffects(Player player) {
        Set<PotionEffectType> previous = appliedSetEffects.remove(player.getUniqueId());
        Map<PotionEffectType, Integer> amplifiers = appliedSetAmplifiers.remove(player.getUniqueId());
        if (previous != null) for (PotionEffectType type : previous) {
            PotionEffect current = player.getPotionEffect(type);
            Integer amplifier = amplifiers == null ? null : amplifiers.get(type);
            if (current != null && current.getDuration() <= 60 && amplifier != null && current.getAmplifier() == amplifier) player.removePotionEffect(type);
        }
    }

    private static PotionEffectType potion(String id) {
        return switch (id.toLowerCase(Locale.ROOT)) {
            case "haste" -> PotionEffectType.HASTE;
            case "fire_resistance" -> PotionEffectType.FIRE_RESISTANCE;
            case "speed", "dolphins_grace", "jump_boost", "slow_falling", "night_vision", "regeneration", "water_breathing", "resistance", "saturation", "strength" -> Registry.POTION_EFFECT_TYPE.get(NamespacedKey.minecraft(id));
            default -> null;
        };
    }

    public Stats stats(ItemStack stack) {
        CustomItem item = plugin.items().customOf(stack);
        if (!eligible(item)) return new Stats(0, 0, 0, 0, 0, 0, 0, 0);
        // Armor set definitions historically used max_health/armor because those
        // values are also written to the vanilla item attributes. Convert them
        // into the native RPG stat units here so the Skills system and the lore
        // agree with the actual equipped gear.
        double health = item.attributes().getOrDefault("health", 0D)
                + item.attributes().getOrDefault("max_health", 0D) * 5.0;
        // Native max_health/armor modifiers remain handled by Minecraft and are not counted twice.
        double defense = item.attributes().getOrDefault("defense", 0D);
        double mana = item.attributes().getOrDefault("mana", 0D);
        double strength = item.attributes().getOrDefault("strength", 0D);
        double crit = item.attributes().getOrDefault("crit_chance", 0D);
        double critDamage = item.attributes().getOrDefault("crit_damage", 0D);
        double speed = item.attributes().getOrDefault("speed", 0D);
        double fortune = item.attributes().getOrDefault("fortune", 0D);
        for (var entry : item.attributes().entrySet()) if (entry.getKey().endsWith("_fortune")) fortune += entry.getValue();
        String reforge = reforgeOf(stack);
        if (reforge != null) switch (reforge) {
            case "wise" -> mana += 50;
            case "titanic" -> health += 25;
            case "heavy" -> defense += 25;
            case "sharp" -> { strength += 5; crit += 10; }
            default -> { }
        }
        return new Stats(health, defense, mana, strength, crit, critDamage, speed, fortune);
    }

    public boolean applyReforge(ItemStack stack, String reforge) {
        if (stack == null || stack.getAmount() != 1 || !eligible(plugin.items().customOf(stack))) return false;
        String id = reforge == null ? "" : reforge.trim().toLowerCase(Locale.ROOT);
        if (!id.isEmpty() && !java.util.Set.of("wise", "titanic", "heavy", "sharp").contains(id)) return false;
        var meta = stack.getItemMeta();
        if (id.isEmpty()) meta.getPersistentDataContainer().remove(reforgeKey);
        else meta.getPersistentDataContainer().set(reforgeKey, PersistentDataType.STRING, id);
        var lore = meta.lore() == null ? new java.util.ArrayList<net.kyori.adventure.text.Component>() : new java.util.ArrayList<>(meta.lore());
        lore.removeIf(line -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line).startsWith("Reforge: "));
        if (!id.isEmpty()) lore.add(gg.aetherfall.core.util.Text.mm("<gold>Reforge: <yellow>" + id));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return true;
    }

    public String reforgeOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(reforgeKey, PersistentDataType.STRING);
    }

    public boolean eligible(CustomItem item) {
        return item != null && (item.type().isArmor() || item.type() == CustomItem.Type.WEAPON || item.type() == CustomItem.Type.BOW || item.type() == CustomItem.Type.TOOL);
    }

    /** Reforge the held item in place, charging resources only after every precondition succeeds. */
    public void open(Player player) {
        var menu = new gg.aetherfall.core.util.Menu(3, gg.aetherfall.core.util.Text.mm("<gold>Equipment Customization"));
        String[] ids = {"wise", "titanic", "heavy", "sharp"};
        String[] bonuses = {"+50 Mana", "+25 Health", "+25 Defense", "+5 Strength, +10% Crit Chance"};
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i], bonus = bonuses[i];
            menu.set(10 + i * 2, new gg.aetherfall.core.util.ItemBuilder(org.bukkit.Material.ANVIL).name("<gold>" + id)
                    .lore(java.util.List.of("<gray>" + bonus, "<gray>Cost: 4 Aether Shards", "<gray>Replaces the current reforge.", "<yellow>Click to reforge your held custom gear.")).build(), (p, click) -> {
                ItemStack held = p.getInventory().getItemInMainHand();
                if (held.getAmount() != 1 || !eligible(plugin.items().customOf(held))) { p.sendMessage(gg.aetherfall.core.util.Text.mm("<red>Hold one custom weapon, tool, or armor piece.")); return; }
                if (id.equals(reforgeOf(held))) { p.sendMessage(gg.aetherfall.core.util.Text.mm("<yellow>This item already has that reforge.")); return; }
                if (plugin.items().count(p, "aether_shard") < 4) { p.sendMessage(gg.aetherfall.core.util.Text.mm("<red>You need 4 Aether Shards.")); return; }
                if (!applyReforge(held, id)) return;
                plugin.items().remove(p, "aether_shard", 4);
                p.getInventory().setItemInMainHand(held);
                plugin.skills().refresh(p, true);
                p.sendMessage(gg.aetherfall.core.util.Text.mm("<green>Applied " + id + " reforge!"));
            });
        }
        menu.fill(org.bukkit.Material.BLACK_STAINED_GLASS_PANE).open(player);
    }

    public void describe(Player player) {
        Stats s = equipped(player);
        player.sendMessage(gg.aetherfall.core.util.Text.mm("<gradient:#FDE68A:#F59E0B><bold>Equipment Stats"));
        player.sendMessage(gg.aetherfall.core.util.Text.mm("<gray>Health: <red>+" + Math.round(s.health()) + "❤  <gray>Defense: <green>+" + Math.round(s.defense())));
        player.sendMessage(gg.aetherfall.core.util.Text.mm("<gray>Mana: <aqua>+" + Math.round(s.mana()) + "  <gray>Strength: <red>+" + Math.round(s.strength()) + "  <gray>Crit Chance: <yellow>+" + Math.round(s.critChance()) + "%"));
        player.sendMessage(gg.aetherfall.core.util.Text.mm("<gray>Crit Damage: <yellow>+" + Math.round(s.critDamage()) + "%  <gray>Speed: <white>+" + Math.round(s.speed()) + "  <gray>Fortune: <gold>+" + Math.round(s.fortune())));
        player.sendMessage(gg.aetherfall.core.util.Text.mm("<dark_gray>Reforges: wise (mana), titanic (health), heavy (defense), sharp (damage/crit)"));
    }

    /** Staff-readable breakdown of every equipment contribution. */
    public java.util.List<String> debugLines(Player player) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("<gradient:#FDE68A:#F59E0B><bold>Equipment Stat Audit</bold>");
        for (ItemStack item : player.getInventory().getArmorContents()) {
            CustomItem custom = plugin.items().customOf(item);
            if (custom == null) continue;
            Stats s = stats(item);
            lines.add("<gray>Armor <white>" + custom.id() + "</white>: Health +" + Math.round(s.health()) + ", Defense +" + Math.round(s.defense())
                    + ", Mana +" + Math.round(s.mana()) + ", Strength +" + Math.round(s.strength()) + ", Crit +" + Math.round(s.critChance()) + "%");
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        CustomItem weapon = plugin.items().customOf(held);
        if (weapon != null) {
            Stats s = stats(held);
            lines.add("<gray>Main hand <white>" + weapon.id() + "</white>: Strength +" + Math.round(s.strength()) + ", Crit +" + Math.round(s.critChance()) + "%, Crit Damage +" + Math.round(s.critDamage()) + "%");
        }
        Stats total = equipped(player);
        lines.add("<aqua>Total equipment: <white>Health +" + Math.round(total.health()) + " · Defense +" + Math.round(total.defense())
                + " · Mana +" + Math.round(total.mana()) + " · Strength +" + Math.round(total.strength())
                + " · Crit +" + Math.round(total.critChance()) + "% · Crit Damage +" + Math.round(total.critDamage()) + "%");
        return lines;
    }
}
