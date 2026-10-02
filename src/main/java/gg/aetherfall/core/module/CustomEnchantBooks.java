package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.items.CustomItem;
import gg.aetherfall.core.util.ItemBuilder;
import gg.aetherfall.core.util.Menu;
import gg.aetherfall.core.util.Text;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Fishing book drops and safe anvil combination for custom enchant books. */
public final class CustomEnchantBooks implements Listener, org.bukkit.command.TabExecutor {
    private final AetherCore plugin;
    private final org.bukkit.NamespacedKey idKey;
    private final org.bukkit.NamespacedKey levelKey;
    private final Map<String, Integer> maxLevels = new java.util.LinkedHashMap<>();
    private final Map<String, Definition> definitions = new java.util.LinkedHashMap<>();
    private final List<String> fishingEnchants = new ArrayList<>();
    /** Catch entity UUIDs make repeated delivery of the same fishing event idempotent. */
    private final java.util.Set<java.util.UUID> awardedCatches = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final CustomEnchantments storage;

    public CustomEnchantBooks(AetherCore plugin) {
        this.plugin = plugin;
        idKey = new org.bukkit.NamespacedKey(plugin, "custom_enchant_book");
        levelKey = new org.bukkit.NamespacedKey(plugin, "custom_enchant_level");
        storage = new CustomEnchantments(plugin);
        reload();
    }

    public void reload() {
        maxLevels.clear(); definitions.clear(); fishingEnchants.clear();
        File file = new File(plugin.getDataFolder(), "custom-enchants.yml");
        if (!file.exists()) plugin.saveResource("custom-enchants.yml", false);
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection enchants = yml.getConfigurationSection("enchants");
        if (enchants == null) return;
        java.util.Set<String> allowedTargets = java.util.Set.of("ARMOR", "SWORD", "BOW", "PICKAXE", "HOE", "FISHING_ROD", "MINION");
        for (String id : enchants.getKeys(false)) {
            ConfigurationSection def = enchants.getConfigurationSection(id);
            if (def == null) continue;
            String normalized = id.toLowerCase(Locale.ROOT);
            if (!normalized.matches("[a-z0-9_]{1,48}")) { plugin.getLogger().warning("custom-enchants.yml: ignoring invalid id '" + id + "'"); continue; }
            if (definitions.containsKey(normalized)) { plugin.getLogger().warning("custom-enchants.yml: duplicate id '" + id + "'"); continue; }
            int max = def.getInt("max-level", 1);
            if (max < 1 || max > 50) { plugin.getLogger().warning("custom-enchants.yml: " + normalized + " max-level must be 1-50"); continue; }
            List<String> applies = def.getStringList("applies-to").stream().map(s -> s.toUpperCase(Locale.ROOT)).toList();
            if (applies.isEmpty() || applies.stream().anyMatch(rule -> !allowedTargets.contains(rule))) { plugin.getLogger().warning("custom-enchants.yml: " + normalized + " has invalid applies-to rules"); continue; }
            String effect = def.getString("effect", "").trim();
            if (effect.isBlank() || !effect.matches("[a-z0-9_]{1,64}")) { plugin.getLogger().warning("custom-enchants.yml: " + normalized + " has an invalid effect key"); continue; }
            double perLevel = def.getDouble("per-level", 0);
            if (!Double.isFinite(perLevel) || perLevel < 0) { plugin.getLogger().warning("custom-enchants.yml: " + normalized + " has an invalid per-level value"); continue; }
            List<String> incompatible = def.getStringList("incompatible-with").stream()
                    .map(s -> s.toLowerCase(Locale.ROOT).trim())
                    .filter(s -> !s.isBlank() && !s.equals(normalized)).distinct().toList();
            Definition definition = new Definition(normalized, def.getString("name", display(normalized)),
                    def.getString("rarity", "COMMON"), max, applies, effect,
                    perLevel, def.getString("description", ""), incompatible);
            definitions.put(normalized, definition);
            maxLevels.put(normalized, max);
            String source = def.getString("source", "");
            if (source.toUpperCase(Locale.ROOT).contains("FISHING")) fishingEnchants.add(normalized);
        }
        Map<String, Definition> repairedDefinitions = new java.util.LinkedHashMap<>();
        for (Definition definition : definitions.values()) {
            List<String> valid = definition.incompatibleWith.stream().filter(definitions::containsKey).toList();
            if (valid.size() != definition.incompatibleWith.size()) plugin.getLogger().warning("custom-enchants.yml: " + definition.id + " references an unknown incompatible enchant");
            if (valid.size() != definition.incompatibleWith.size()) {
                Definition repaired = new Definition(definition.id, definition.name, definition.rarity, definition.maxLevel,
                        definition.appliesTo, definition.effect, definition.perLevel, definition.description, valid);
                repairedDefinitions.put(definition.id, repaired);
            }
        }
        definitions.putAll(repairedDefinitions);
        plugin.getLogger().info("Loaded " + definitions.size() + " validated custom enchant definitions (" + fishingEnchants.size() + " fishing sources).");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void fish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof org.bukkit.entity.Item)) return;
        Player player = event.getPlayer();
        if (player.getGameMode() != org.bukkit.GameMode.SURVIVAL || fishingEnchants.isEmpty()) return;
        if (!(event.getCaught() instanceof org.bukkit.entity.Item caught)) return;
        if (awardedCatches.size() > 100_000) awardedCatches.clear();
        if (!awardedCatches.add(caught.getUniqueId())) return;
        double chance = plugin.getConfig().getDouble("fishing.custom-enchant-book-chance", 0.0125);
        var data = plugin.data().get(player);
        int fishingLevel = data == null ? 0 : plugin.skills().level(data, "fishing");
        chance += fishingLevel * plugin.getConfig().getDouble("fishing.custom-enchant-level-chance", 0.00025);
        if (ThreadLocalRandom.current().nextDouble() >= Math.min(0.08, chance)) return;
        String id = fishingEnchants.get(ThreadLocalRandom.current().nextInt(fishingEnchants.size()));
        int max = maxLevels.getOrDefault(id, 1);
        int level = rollLevel(max);
        ItemStack book = createBook(id, level);
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(book);
        overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        player.sendMessage(Text.mm("<aqua>✦ Fishing discovery: <gold>" + display(id) + " " + roman(level) + " <gray>enchanted book!"));
    }

    private int rollLevel(int max) {
        if (max <= 1) return 1;
        int level = 1;
        for (int i = 2; i <= max; i++) if (ThreadLocalRandom.current().nextDouble() < 0.25 / i) level = i;
        return level;
    }

    public ItemStack createBook(String id, int level) {
        id = id.toLowerCase(Locale.ROOT);
        level = Math.max(1, Math.min(maxLevels.getOrDefault(id, 1), level));
        Definition definition = definitions.get(id);
        String name = definition == null ? display(id) : definition.name;
        String description = definition == null ? "Custom Enchant" : definition.description;
        ItemStack book = new ItemBuilder(Material.ENCHANTED_BOOK).name("<aqua>" + name + " " + roman(level) + " <gray>Book")
                .lore(List.of("<gray>" + description, "", "<yellow>Use <white>/enchants</white> to apply this book.", "<gray>Combine two matching books in an anvil to upgrade.")).build();
        ItemMeta meta = book.getItemMeta();
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id);
        meta.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, level);
        book.setItemMeta(meta);
        return book;
    }

    @EventHandler public void prepare(PrepareAnvilEvent event) {
        ItemStack first = event.getInventory().getItem(0), second = event.getInventory().getItem(1);
        Book a = read(first), b = read(second);
        if (a == null || b == null || !a.id.equals(b.id)) return;
        int max = maxLevels.getOrDefault(a.id, 1);
        if (a.level >= max || b.level >= max || a.level != b.level) { event.setResult(null); return; }
        int resultLevel = a.level + 1;
        event.setResult(createBook(a.id, resultLevel));
        event.getInventory().setRepairCost(Math.max(1, resultLevel * 5));
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof AnvilInventory) || event.getRawSlot() != 2 || !(event.getWhoClicked() instanceof Player)) return;
        ItemStack result = event.getCurrentItem();
        if (read(result) == null) return;
        // Bukkit consumes the two input slots and gives the prepared result.
        // The result was created by us and already contains validated PDC data.
    }

    private Book read(ItemStack stack) {
        if (stack == null || stack.getType() != Material.ENCHANTED_BOOK || !stack.hasItemMeta()) return null;
        var pdc = stack.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(idKey, PersistentDataType.STRING);
        Integer level = pdc.get(levelKey, PersistentDataType.INTEGER);
        if (id != null) id = id.toLowerCase(Locale.ROOT);
        if (id == null || level == null || !maxLevels.containsKey(id) || level < 1 || level > maxLevels.get(id)) return null;
        return new Book(id, level);
    }

    private boolean applies(Definition definition, ItemStack target) {
        if (definition == null || target == null || target.getType().isAir()) return false;
        CustomItem custom = plugin.items().customOf(target);
        String material = target.getType().name();
        for (String rule : definition.appliesTo) {
            if (rule.equals("MINION")) continue; // Minions need a placed-item implementation before they can hold enchants.
            if (rule.equals("ARMOR") && (material.endsWith("_HELMET") || material.endsWith("_CHESTPLATE") || material.endsWith("_LEGGINGS") || material.endsWith("_BOOTS") || (custom != null && custom.type().isArmor()))) return true;
            if (rule.equals("SWORD") && (material.endsWith("_SWORD") || (custom != null && custom.type() == CustomItem.Type.WEAPON))) return true;
            if (rule.equals("BOW") && (material.equals("BOW") || material.equals("CROSSBOW") || (custom != null && custom.type() == CustomItem.Type.BOW))) return true;
            if (rule.equals("PICKAXE") && material.endsWith("_PICKAXE")) return true;
            if (rule.equals("HOE") && material.endsWith("_HOE")) return true;
            if (rule.equals("FISHING_ROD") && material.equals("FISHING_ROD")) return true;
        }
        return false;
    }

    private void appendLore(ItemStack target, Definition definition, int level) {
        ItemMeta meta = target.getItemMeta();
        if (meta == null) return;
        List<net.kyori.adventure.text.Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        String prefix = definition.name + " ";
        lore.removeIf(line -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line).startsWith(prefix));
        lore.add(Text.mm("<aqua>✦ " + definition.name + " " + roman(level)));
        meta.lore(lore);
        target.setItemMeta(meta);
    }

    private void applyFromSlot(Player player, int slot) {
        if (slot < 0 || slot >= player.getInventory().getSize() || slot == player.getInventory().getHeldItemSlot()) {
            player.sendMessage(Text.mm("<red>Select the enchant book from your inventory, not the held item."));
            return;
        }
        ItemStack bookStack = player.getInventory().getItem(slot);
        Book book = read(bookStack);
        if (book == null) { player.sendMessage(Text.mm("<red>Select a valid custom enchant book from your inventory.")); return; }
        Definition definition = definitions.get(book.id);
        ItemStack target = player.getInventory().getItemInMainHand();
        if (!applies(definition, target)) {
            player.sendMessage(Text.mm("<red>" + definition.name + " cannot be applied to the item in your main hand."));
            return;
        }
        Map<String, Integer> existing = storage.levels(target);
        for (String incompatible : definition.incompatibleWith) {
            if (existing.containsKey(incompatible)) {
                Definition other = definitions.get(incompatible);
                String otherName = other == null ? display(incompatible) : other.name;
                player.sendMessage(Text.mm("<red>" + definition.name + " is incompatible with " + otherName + "."));
                return;
            }
        }
        int current = existing.getOrDefault(book.id, 0);
        if (current >= book.level) {
            player.sendMessage(Text.mm("<yellow>Your item already has " + definition.name + " " + roman(current) + " or higher."));
            return;
        }
        storage.apply(target, book.id, book.level);
        appendLore(target, definition, book.level);
        player.getInventory().setItemInMainHand(target);
        bookStack.setAmount(bookStack.getAmount() - 1);
        player.getInventory().setItem(slot, bookStack.getAmount() <= 0 ? null : bookStack);
        player.sendMessage(Text.mm("<green>Applied <aqua>" + definition.name + " " + roman(book.level) + "</aqua> to your item."));
        plugin.skills().refresh(player, true);
    }

    private void openMenu(Player player) {
        Menu menu = new Menu(6, Text.mm("<dark_gray>Aetherfall · Custom Enchants"));
        ItemStack held = player.getInventory().getItemInMainHand();
        menu.set(4, held == null || held.getType().isAir()
                ? new ItemBuilder(Material.BARRIER).name("<red>No item held").lore(List.of("<gray>Hold the target item in your main hand.")).build()
                : held.clone());
        menu.set(49, new ItemBuilder(Material.BOOK).name("<aqua>How to apply").lore(List.of("<gray>Hold your target item in your main hand.", "<gray>Click a custom enchant book in your inventory.", "<gray>The book is consumed only after validation.", "<gray>Use an anvil to combine matching books.")).build());
        int slot = 9;
        for (Definition definition : definitions.values()) {
            if (slot >= 45) break;
            List<String> lore = List.of("<gray>Rarity: " + definition.rarity, "<gray>Max level: <white>" + roman(definition.maxLevel), "", "<gray>" + definition.description, "", "<dark_gray>Source: " + definition.effect);
            menu.set(slot++, new ItemBuilder(Material.ENCHANTED_BOOK).name("<aqua>" + definition.name).lore(lore).build());
        }
        menu.onBottomClick((p, clicked) -> applyFromSlot(p, clicked));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(player);
    }

    /** Staff-facing payload audit; lore and display names are intentionally ignored. */
    public String audit(Player player) {
        int checked = 0, issues = 0;
        for (ItemStack item : auditItems(player)) {
            if (item == null || item.getType().isAir()) continue;
            Map<String, Integer> levels = storage.levels(item);
            if (levels.isEmpty()) continue;
            checked++;
            for (Map.Entry<String, Integer> entry : levels.entrySet()) {
                Definition definition = definitions.get(entry.getKey());
                if (definition == null || entry.getValue() > definition.maxLevel || !applies(definition, item)) { issues++; continue; }
                for (String incompatible : definition.incompatibleWith) if (levels.containsKey(incompatible)) issues++;
            }
        }
        return checked + " enchanted item(s) checked; " + issues + " issue(s) found.";
    }

    /** Removes unknown, over-level, invalid-target, and incompatible payload entries. */
    public int repair(Player player) {
        int repaired = 0;
        for (ItemStack item : auditItems(player)) {
            if (item == null || item.getType().isAir()) continue;
            Map<String, Integer> before = storage.levels(item);
            if (before.isEmpty()) continue;
            Map<String, Integer> clean = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Integer> entry : before.entrySet()) {
                Definition definition = definitions.get(entry.getKey());
                if (definition == null || entry.getValue() > definition.maxLevel || !applies(definition, item)) continue;
                boolean conflict = false;
                for (String incompatible : definition.incompatibleWith) if (clean.containsKey(incompatible)) { conflict = true; break; }
                if (!conflict) clean.put(entry.getKey(), entry.getValue());
            }
            if (!clean.equals(before)) { storage.replace(item, clean); repaired++; }
        }
        if (repaired > 0) plugin.skills().refresh(player, true);
        return repaired;
    }

    private List<ItemStack> auditItems(Player player) {
        List<ItemStack> items = new ArrayList<>(java.util.Arrays.asList(player.getInventory().getContents()));
        items.addAll(java.util.Arrays.asList(player.getInventory().getArmorContents()));
        items.add(player.getInventory().getItemInOffHand());
        return items;
    }

    private static String display(String id) { String[] words = id.split("_"); StringBuilder out = new StringBuilder(); for (String word : words) { if (!out.isEmpty()) out.append(' '); out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)); } return out.toString(); }
    private static String roman(int n) { String[] s = {"", "I", "II", "III", "IV", "V"}; return n < s.length ? s[n] : Integer.toString(n); }

    @Override public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (sender instanceof Player player && (args.length == 0 || !args[0].equalsIgnoreCase("list"))) { openMenu(player); return true; }
        sender.sendMessage(Text.mm("<gradient:#8B5CF6:#22D3EE><bold>Aetherfall Custom Enchants</bold>"));
        sender.sendMessage(Text.mm("<gray>Fishing can discover: <aqua>" + fishingEnchants.stream().map(id -> definitions.get(id).name).collect(java.util.stream.Collectors.joining(", "))));
        sender.sendMessage(Text.mm("<gray>Hold a target item, then click a book in your inventory from <yellow>/enchants</yellow>."));
        sender.sendMessage(Text.mm("<gray>Combine two matching enchanted books in an anvil to create the next level."));
        sender.sendMessage(Text.mm("<dark_gray>Fishing book chance: <white>" + String.format(Locale.ROOT, "%.2f%%", plugin.getConfig().getDouble("fishing.custom-enchant-book-chance", 0.0125) * 100)));
        return true;
    }

    @Override public List<String> onTabComplete(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String alias, String[] args) { return List.of(); }
    private record Definition(String id, String name, String rarity, int maxLevel, List<String> appliesTo, String effect, double perLevel, String description, List<String> incompatibleWith) { }
    private record Book(String id, int level) { }
}
