package gg.aetherfall.core.items;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.items.CustomItem.Rarity;
import gg.aetherfall.core.items.CustomItem.Type;
import gg.aetherfall.core.util.Text;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads custom items from items.yml and builds/identifies their ItemStacks.
 * Keys used across the economy: lowercase = custom item id, UPPERCASE = vanilla material.
 */
public final class ItemRegistry {
    public record ArmorSet(String id, String name, Map<String, Integer> effects, List<String> bonusLore) {}

    private final AetherCore plugin;
    private final NamespacedKey idKey;
    private final Map<String, CustomItem> items = new LinkedHashMap<>();
    private final Map<String, ArmorSet> sets = new LinkedHashMap<>();
    private final Map<String, ItemStack> cache = new LinkedHashMap<>();

    public ItemRegistry(AetherCore plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "id");
        reload();
    }

    public void reload() {
        items.clear();
        sets.clear();
        cache.clear();
        File file = new File(plugin.getDataFolder(), "items.yml");
        if (!file.exists()) plugin.saveResource("items.yml", false);
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yml.getConfigurationSection("items");
        if (sec != null) for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s != null) register(parse(id.toLowerCase(Locale.ROOT), s));
        }
        ConfigurationSection setSec = yml.getConfigurationSection("sets");
        if (setSec != null) for (String id : setSec.getKeys(false)) loadSet(id, setSec.getConfigurationSection(id));
        plugin.getLogger().info("Loaded " + items.size() + " custom items and " + sets.size() + " armor sets.");
    }

    private void register(CustomItem item) {
        if (item != null) items.put(item.id(), item);
    }

    private CustomItem parse(String id, ConfigurationSection s) {
        Material mat = Material.matchMaterial(s.getString("material", "PAPER"));
        if (mat == null || !mat.isItem()) {
            plugin.getLogger().warning("items.yml: '" + id + "' has an invalid material");
            return null;
        }
        Type type;
        Rarity rarity;
        try {
            type = Type.valueOf(s.getString("type", "RESOURCE").toUpperCase(Locale.ROOT));
            rarity = Rarity.valueOf(s.getString("rarity", "COMMON").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("items.yml: '" + id + "' has an invalid type/rarity");
            return null;
        }
        Map<String, Integer> recipe = intMap(s.getConfigurationSection("recipe.ingredients"));
        Color color = null;
        if (s.isString("color")) color = Color.fromRGB(Integer.parseInt(s.getString("color").replace("#", ""), 16));
        return new CustomItem(id, mat, s.getString("name", id), rarity, type, s.getStringList("lore"),
                s.getBoolean("glint", type == Type.ENCHANTED), intMap(s.getConfigurationSection("enchants")),
                doubleMap(s.getConfigurationSection("attributes")), s.getBoolean("unbreakable"),
                s.getString("ability"), s.getString("ability-name"), s.getString("ability-trigger", ""),
                s.getStringList("ability-lore"), s.getString("set"), intMap(s.getConfigurationSection("effects")),
                recipe, s.getInt("recipe.amount", 1), s.getDouble("sell", 0), color,
                s.getString("category", defaultCategory(type)));
    }

    /** Sets expand into 4 dyed-leather armor pieces with generated recipes. */
    private void loadSet(String id, ConfigurationSection s) {
        if (s == null) return;
        String name = s.getString("name", id);
        sets.put(id, new ArmorSet(id, name, intMap(s.getConfigurationSection("effects")), s.getStringList("bonus-lore")));
        List<Integer> armor = s.getIntegerList("armor");
        double toughness = s.getDouble("toughness", 0);
        double health = s.getDouble("health", 0);
        Rarity rarity = Rarity.valueOf(s.getString("rarity", "RARE").toUpperCase(Locale.ROOT));
        Color color = Color.fromRGB(Integer.parseInt(s.getString("color", "#FFFFFF").replace("#", ""), 16));
        String matKey = s.getString("recipe-material");
        int cost = s.getInt("recipe-cost", 1);
        Map<String, Integer> extra = intMap(s.getConfigurationSection("recipe-extra"));
        Map<String, Double> stats = doubleMap(s.getConfigurationSection("stats"));
        String[] pieces = {"helmet", "chestplate", "leggings", "boots"};
        Material[] mats = {Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS};
        Type[] types = {Type.HELMET, Type.CHESTPLATE, Type.LEGGINGS, Type.BOOTS};
        int[] mult = {5, 8, 7, 4};
        String[] labels = {"Helmet", "Chestplate", "Leggings", "Boots"};
        for (int i = 0; i < 4; i++) {
            Map<String, Double> attrs = new LinkedHashMap<>();
            attrs.put("armor", (double) (armor.size() > i ? armor.get(i) : 2));
            if (toughness > 0) attrs.put("armor_toughness", toughness);
            // RPG stats are totals for the complete set in items.yml. Spread
            // them across four pieces so partial sets remain useful.
            for (var stat : stats.entrySet()) attrs.put(stat.getKey(), stat.getValue() / 4.0);
            if (health > 0 && !stats.containsKey("health")) attrs.put("health", health);
            Map<String, Integer> recipe = new LinkedHashMap<>();
            if (matKey != null) recipe.put(matKey, cost * mult[i]);
            for (var e : extra.entrySet()) recipe.put(e.getKey(), Math.max(1, e.getValue() * mult[i] / 8));
            String pid = id + "_" + pieces[i];
            register(new CustomItem(pid, mats[i], name + " " + labels[i], rarity, types[i], s.getStringList("lore"),
                    false, intMap(s.getConfigurationSection("enchants")), attrs, true, null, null, "", List.of(),
                    id, Map.of(), recipe, 1, s.getDouble("sell", 0) * mult[i], color, "ARMOR"));
        }
    }

    private static String defaultCategory(Type type) {
        return switch (type) {
            case ENCHANTED -> "ENCHANTED";
            case TOOL -> "TOOLS";
            case WEAPON, BOW -> "WEAPONS";
            case HELMET, CHESTPLATE, LEGGINGS, BOOTS -> "ARMOR";
            case TALISMAN -> "TALISMANS";
            case CONSUMABLE -> "CONSUMABLES";
            default -> "RESOURCES";
        };
    }

    private static Map<String, Integer> intMap(ConfigurationSection s) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (s != null) for (String k : s.getKeys(false)) m.put(k, s.getInt(k));
        return m;
    }

    private static Map<String, Double> doubleMap(ConfigurationSection s) {
        Map<String, Double> m = new LinkedHashMap<>();
        if (s != null) for (String k : s.getKeys(false)) m.put(k, s.getDouble(k));
        return m;
    }

    // ── lookup ────────────────────────────────────────────────

    public CustomItem get(String id) {
        return id == null ? null : items.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<CustomItem> all() {
        return items.values();
    }

    public ArmorSet set(String id) {
        return sets.get(id);
    }

    public NamespacedKey idKey() {
        return idKey;
    }

    /** Custom id of a stack, or null for vanilla items. */
    public String idOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    public CustomItem customOf(ItemStack stack) {
        return get(idOf(stack));
    }

    /**
     * Economy key for a stack: custom id, or the material name for a plain vanilla item
     * (no custom name, enchants or damage). Returns null for anything else.
     */
    public String keyOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return null;
        String id = idOf(stack);
        if (id != null) return items.containsKey(id) ? id : null;
        return stack.isSimilar(new ItemStack(stack.getType())) ? stack.getType().name() : null;
    }

    /**
     * True for keys that may be traded on the Bazaar / NPC shop: vanilla items and custom
     * materials. Custom gear (weapons, armor, tools, talismans, relics) goes to the Auction House.
     */
    public boolean isCommodity(String key) {
        CustomItem c = items.get(key);
        if (c == null) return isValidKey(key);
        return c.type() == Type.RESOURCE || c.type() == Type.ENCHANTED;
    }

    public boolean isValidKey(String key) {
        if (key == null) return false;
        if (items.containsKey(key)) return true;
        Material m = Material.matchMaterial(key);
        return m != null && m.isItem() && !m.isAir() && key.equals(key.toUpperCase(Locale.ROOT));
    }

    public ItemStack stack(String key, int amount) {
        CustomItem c = items.get(key);
        if (c != null) {
            ItemStack s = build(c);
            s.setAmount(Math.max(1, amount));
            return s;
        }
        Material m = Material.matchMaterial(key);
        return new ItemStack(m == null ? Material.BARRIER : m, Math.max(1, amount));
    }

    public int maxStack(String key) {
        return stack(key, 1).getMaxStackSize();
    }

    /** MiniMessage display name for any economy key. */
    public String displayName(String key) {
        CustomItem c = items.get(key);
        if (c != null) return c.coloredName();
        Material m = Material.matchMaterial(key);
        if (m == null) return key;
        return "<white>" + prettify(m.name());
    }

    public static String prettify(String enumName) {
        StringBuilder sb = new StringBuilder();
        for (String w : enumName.toLowerCase(Locale.ROOT).split("_")) {
            if (w.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    public int count(Player player, String key) {
        int n = 0;
        for (ItemStack s : player.getInventory().getStorageContents()) {
            if (key.equals(keyOf(s))) n += s.getAmount();
        }
        return n;
    }

    /** Removes up to amount; returns how many were removed. */
    public int remove(Player player, String key, int amount) {
        int left = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack s = contents[i];
            if (!key.equals(keyOf(s))) continue;
            int take = Math.min(left, s.getAmount());
            s.setAmount(s.getAmount() - take);
            if (s.getAmount() <= 0) contents[i] = null;
            left -= take;
        }
        player.getInventory().setStorageContents(contents);
        return amount - left;
    }

    /** How many of key fit into the player's inventory right now. */
    public int space(Player player, String key) {
        ItemStack proto = stack(key, 1);
        int max = proto.getMaxStackSize();
        int n = 0;
        for (ItemStack s : player.getInventory().getStorageContents()) {
            if (s == null || s.getType().isAir()) n += max;
            else if (s.isSimilar(proto)) n += Math.max(0, max - s.getAmount());
        }
        return n;
    }

    /** Gives items, dropping any overflow at the player's feet. */
    public void give(Player player, String key, int amount) {
        int max = maxStack(key);
        while (amount > 0) {
            int n = Math.min(max, amount);
            amount -= n;
            for (ItemStack over : player.getInventory().addItem(stack(key, n)).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), over);
            }
        }
    }

    // ── building ──────────────────────────────────────────────

    public ItemStack build(CustomItem c) {
        ItemStack cached = cache.get(c.id());
        if (cached != null) return cached.clone();
        ItemStack stack = new ItemStack(c.material());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Text.item(c.coloredName(), Map.of()));
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, c.id());
        // The resource pack selects each item's texture by this string (vanilla look without the pack).
        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(c.id()));
        meta.setCustomModelDataComponent(cmd);
        if (c.glint()) meta.setEnchantmentGlintOverride(true);
        if (c.unbreakable()) meta.setUnbreakable(true);
        for (var e : c.enchants().entrySet()) {
            Enchantment ench = io.papermc.paper.registry.RegistryAccess.registryAccess()
                    .getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT).get(NamespacedKey.minecraft(e.getKey().toLowerCase(Locale.ROOT)));
            if (ench != null) meta.addEnchant(ench, e.getValue(), true);
        }
        EquipmentSlotGroup slot = switch (c.type()) {
            case HELMET -> EquipmentSlotGroup.HEAD;
            case CHESTPLATE -> EquipmentSlotGroup.CHEST;
            case LEGGINGS -> EquipmentSlotGroup.LEGS;
            case BOOTS -> EquipmentSlotGroup.FEET;
            default -> EquipmentSlotGroup.MAINHAND;
        };
        for (var e : c.attributes().entrySet()) {
            // AetherCore owns RPG Health/Defense/Speed. Do not also write these
            // values as vanilla modifiers or they would be applied twice.
            if (switch (e.getKey().toLowerCase(Locale.ROOT)) {
                case "max_health", "armor", "armor_toughness", "speed", "fortune", "crit_chance", "crit_damage",
                        "health", "defense", "mana", "strength" -> true;
                default -> false;
            }) continue;
            Attribute attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(e.getKey().toLowerCase(Locale.ROOT)));
            if (attr == null) continue;
            double value = e.getValue();
            // Vanilla shows base values; modifiers are relative to the player's base (1 damage, 4 speed).
            if (e.getKey().equalsIgnoreCase("attack_damage")) value -= 1;
            if (e.getKey().equalsIgnoreCase("attack_speed")) value -= 4;
            meta.addAttributeModifier(attr, new AttributeModifier(new NamespacedKey(plugin, c.id() + "_" + e.getKey().toLowerCase(Locale.ROOT)),
                    value, AttributeModifier.Operation.ADD_NUMBER, slot));
        }
        if (c.color() != null && meta instanceof LeatherArmorMeta leather) leather.setColor(c.color());
        meta.lore(Text.lore(buildLore(c), Map.of()));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_DYE);
        if (!c.enchants().isEmpty() && c.glint()) meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        stack.setItemMeta(meta);
        cache.put(c.id(), stack.clone());
        return stack;
    }

    private List<String> buildLore(CustomItem c) {
        List<String> lore = new ArrayList<>();
        Double dmg = c.attributes().get("attack_damage");
        Double armor = c.attributes().get("armor");
        Double tough = c.attributes().get("armor_toughness");
        Double hp = c.attributes().get("max_health");
        Double rpgHealth = c.attributes().get("health");
        Double defense = c.attributes().get("defense");
        Double strength = c.attributes().get("strength");
        Double mana = c.attributes().get("mana");
        Double crit = c.attributes().get("crit_chance");
        Double farmingFortune = c.attributes().get("farming_fortune");
        Double farmingSpeed = c.attributes().get("farming_speed");
        Double spd = c.attributes().get("attack_speed");
        if (dmg != null) lore.add("<gray>Damage: <red>+" + trim(dmg));
        if (spd != null) lore.add("<gray>Attack Speed: <yellow>" + trim(spd));
        if (armor != null) lore.add("<gray>Defense: <green>+" + trim(armor));
        if (tough != null) lore.add("<gray>Toughness: <green>+" + trim(tough));
        if (hp != null) lore.add("<gray>Health: <red>+" + trim(hp / 2) + "❤");
        if (rpgHealth != null) lore.add("<gray>Health: <red>+" + trim(rpgHealth / 5) + "❤");
        if (defense != null) lore.add("<gray>Defense: <green>+" + trim(defense));
        if (strength != null) lore.add("<gray>Strength: <red>+" + trim(strength));
        if (mana != null) lore.add("<gray>Intelligence: <aqua>+" + trim(mana));
        if (crit != null) lore.add("<gray>Crit Chance: <yellow>+" + trim(crit) + "%");
        if (farmingFortune != null) lore.add("<gray>Farming Fortune: <gold>+" + trim(farmingFortune));
        if (farmingSpeed != null) lore.add("<gray>Farming Speed: <yellow>+" + trim(farmingSpeed));
        for (var stat : c.attributes().entrySet()) {
            if (stat.getKey().endsWith("_fortune") && !stat.getKey().equals("farming_fortune"))
                lore.add("<gray>" + prettify(stat.getKey().replace("_fortune", "")) + " Fortune: <gold>+" + trim(stat.getValue()));
        }
        if (!c.enchants().isEmpty() && !(c.type() == Type.ENCHANTED)) {
            if (!lore.isEmpty()) lore.add("");
            StringBuilder sb = new StringBuilder("<blue>");
            int i = 0;
            for (var e : c.enchants().entrySet()) {
                if (i++ > 0) sb.append(", ");
                sb.append(prettify(e.getKey())).append(' ').append(roman(e.getValue()));
            }
            lore.add(sb.toString());
        }
        if (!c.lore().isEmpty()) {
            if (!lore.isEmpty()) lore.add("");
            for (String l : c.lore()) lore.add("<gray>" + l);
        }
        if (c.ability() != null && c.abilityName() != null) {
            lore.add("");
            lore.add("<gold>Ability: " + c.abilityName() + (c.abilityTrigger().isEmpty() ? "" : " <yellow><bold>" + c.abilityTrigger()));
            for (String l : c.abilityLore()) lore.add("<gray>" + l);
        }
        if (!c.effects().isEmpty() && c.type() == Type.TALISMAN) {
            lore.add("");
            lore.add("<gold>Passive <gray>(keep in inventory)");
            for (var e : c.effects().entrySet()) lore.add("<gray>Grants <aqua>" + prettify(e.getKey()) + " " + roman(e.getValue() + 1));
        }
        ArmorSet set = c.set() == null ? null : sets.get(c.set());
        if (set != null) {
            lore.add("");
            lore.add("<gold>Full Set Bonus: " + set.name());
            for (String l : set.bonusLore()) lore.add("<gray>" + l);
        }
        lore.add("");
        lore.add(c.rarity().color + "<bold>" + c.rarity().name() + " " + c.type().label());
        return lore;
    }

    private static String trim(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    public static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }
}
