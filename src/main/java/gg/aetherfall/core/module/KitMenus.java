package gg.aetherfall.core.module;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.Kit;
import com.earth2me.essentials.Kits;
import com.earth2me.essentials.User;
import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.util.ItemBuilder;
import gg.aetherfall.core.util.Menu;
import gg.aetherfall.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A safe, rank-aware GUI over EssentialsX's kit service with rich previewing. */
public final class KitMenus implements TabExecutor {
    private final AetherCore plugin;

    public KitMenus(AetherCore plugin) { this.plugin = plugin; }

    private Essentials essentials() {
        return (Essentials) Bukkit.getPluginManager().getPlugin("Essentials");
    }

    public void open(Player player) {
        Essentials ess;
        try { ess = essentials(); }
        catch (LinkageError unavailable) { openNative(player); return; }
        if (ess == null) {
            openNative(player);
            return;
        }
        Kits kits = ess.getKits();
        List<String> names = new ArrayList<>(kits.getKitKeys());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        Menu menu = new Menu(4, Text.mm("<dark_gray>Aetherfall · Rank Kits"));
        int slot = 10;
        for (String name : names) {
            if (slot == 17 || slot == 26) slot += 2;
            boolean allowed = player.hasPermission("essentials.kits." + name);
            Material icon = icon(name);
            String rank = rank(name);
            long next = allowed ? nextUse(ess, player, name) : 0;
            List<String> allLines = contents(kits, name);

            List<String> lore = new ArrayList<>();
            lore.add(rank.isBlank() ? "<gray>Useful supplies for your journey." : "<gray>Unlock: <white>" + rank + " rank");
            lore.add("");
            lore.add("<gray>Contains (" + allLines.size() + " rewards):");
            int maxPreview = 8;
            for (int i = 0; i < Math.min(allLines.size(), maxPreview); i++) {
                lore.add(allLines.get(i));
            }
            if (allLines.size() > maxPreview) {
                lore.add("<dark_gray>• <gray>... and <yellow>" + (allLines.size() - maxPreview) + " more rewards</yellow>");
            }
            lore.add("");
            if (!allowed) {
                lore.add("<red>✖ Locked");
                lore.add("<gray>Earn the required rank by playing.");
            } else if (next < 0) {
                lore.add("<dark_gray>✔ Already claimed");
            } else if (next > System.currentTimeMillis()) {
                lore.add("<gold>⌛ Ready in " + Text.duration((next - System.currentTimeMillis()) / 1000));
            } else {
                lore.add("<green>✔ Ready to claim");
                lore.add("<yellow>▶ Left-click to claim");
            }
            lore.add("<aqua>▶ Right-click to preview kit");

            allowed = allowed && next >= 0 && next <= System.currentTimeMillis();
            final boolean ready = allowed;
            ItemBuilder item = new ItemBuilder(icon).name((ready ? "<green>" : allowed ? "<aqua>" : "<dark_gray>") + "<bold>" + title(name)).lore(lore);
            menu.set(slot, item.glow(ready).build(), (p, click) -> {
                if (click.isRightClick()) {
                    openPreview(p, name);
                    return;
                }
                if (ready) {
                    claim(p, name);
                    open(p);
                } else if (p.hasPermission("essentials.kits." + name)) {
                    p.sendMessage(Text.mm("<gray>That kit is on cooldown. <dark_gray>/kits"));
                } else {
                    p.sendMessage(Text.mm("<gray>That kit is locked. Keep playing to unlock it."));
                }
            });
            slot++;
        }
        menu.set(31, new ItemBuilder(Material.BOOK).name("<gold><bold>How kits work")
                .lore(List.of("<gray>Every kit refreshes <white>weekly<gray>.", "<gray>Kits stack: each rank you reach", "<gray>adds its own kit on top of the", "<gray>ones below it.", "", "<yellow>Right-click any kit to preview its contents.")).build());
        menu.set(27, new ItemBuilder(Material.ARROW).name("<gray>← Back").build(), (p, c) -> plugin.menus().openMain(p));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(player);
    }

    /** Opens an interactive preview GUI of the kit's exact items */
    public void openPreview(Player player, String name) {
        Essentials ess;
        try { ess = essentials(); }
        catch (LinkageError unavailable) { openNativePreview(player, name); return; }
        if (ess == null) { openNativePreview(player, name); return; }
        Kits kits = ess.getKits();
        Object raw = kits.getKit(name) == null ? null : kits.getKit(name).get("items");
        if (!(raw instanceof List<?> lines)) {
            player.sendMessage(Text.mm("<red>No preview available for that kit."));
            return;
        }

        Menu preview = new Menu(5, Text.mm("<dark_gray>Kit Preview · " + title(name)));
        int slot = 0;
        for (Object o : lines) {
            if (slot >= 36) break;
            String line = String.valueOf(o).trim();
            String[] parts = line.split(" +");
            if (parts.length == 0 || parts[0].isBlank()) continue;

            ItemStack stack = null;
            if (parts[0].equalsIgnoreCase("/eco") && parts.length >= 4) {
                long coins;
                try { coins = Long.parseLong(parts[3]); } catch (Exception e) { coins = 0; }
                stack = new ItemBuilder(Material.GOLD_INGOT)
                        .name("<gold><bold>" + Text.number(coins) + " Coins")
                        .lore(List.of("<gray>Direct coin deposit to your balance upon claiming.")).build();
            } else if ((parts[0].equalsIgnoreCase("/aegive") || parts[0].equalsIgnoreCase("/aethergive") || parts[0].equalsIgnoreCase("/cgive"))
                    && parts.length >= 3 && plugin.items().isValidKey(parts[2])) {
                int qty = 1;
                try { if (parts.length > 3) qty = Integer.parseInt(parts[3]); } catch (Exception ignored) {}
                stack = plugin.items().stack(parts[2], qty);
            } else if (parts[0].equalsIgnoreCase("/aether") && parts.length >= 4 && parts[1].equalsIgnoreCase("give") && plugin.items().isValidKey(parts[3])) {
                int qty = 1;
                try { if (parts.length > 4) qty = Integer.parseInt(parts[4]); } catch (Exception ignored) {}
                stack = plugin.items().stack(parts[3], qty);
            } else if (!parts[0].startsWith("/")) {
                try {
                    Material mat = Material.matchMaterial(parts[0]);
                    if (mat != null) {
                        int qty = 1;
                        if (parts.length > 1) {
                            try { qty = Integer.parseInt(parts[1]); } catch (Exception ignored) {}
                        }
                        ItemBuilder b = new ItemBuilder(mat, Math.min(64, Math.max(1, qty)));
                        for (int i = 2; i < parts.length; i++) {
                            if (parts[i].startsWith("name:")) {
                                b.name(parts[i].substring(5).replace('_', ' '));
                            }
                        }
                        stack = b.build();
                    }
                } catch (Exception ignored) {}
            }

            if (stack != null) {
                preview.set(slot++, stack);
            }
        }

        boolean allowed = player.hasPermission("essentials.kits." + name);
        long next = allowed ? nextUse(ess, player, name) : 0;
        boolean ready = allowed && next >= 0 && next <= System.currentTimeMillis();

        preview.set(36, new ItemBuilder(Material.ARROW).name("<gray>← Back to Kits").build(), (p, c) -> open(p));
        preview.set(40, new ItemBuilder(ready ? Material.EMERALD_BLOCK : Material.REDSTONE_BLOCK)
                .name(ready ? "<green><bold>Claim This Kit" : "<red><bold>" + (allowed ? "On Cooldown" : "Locked"))
                .lore(List.of(ready ? "<yellow>Click to claim this kit now!" : "<gray>Return when this kit is unlocked and ready.")).build(),
                (p, c) -> {
                    if (ready) {
                        claim(p, name);
                        open(p);
                    }
                });

        preview.fill(Material.BLACK_STAINED_GLASS_PANE).open(player);
    }

    private void claim(Player player, String name) {
        Essentials ess;
        try { ess = essentials(); }
        catch (LinkageError unavailable) { claimNative(player, name); return; }
        if (ess == null) { claimNative(player, name); return; }
        try {
            User user = ess.getUser(player);
            Kit kit = new Kit(name, ess);
            kit.checkPerms(user);
            kit.checkDelay(user);
            kit.checkAffordable(user);
            if (kit.expandItems(user)) {
                kit.chargeUser(user);
                kit.setTime(user);
                player.sendMessage(Text.mm("<green>✔ Claimed the <aqua>" + title(name) + " <green>kit."));
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
            }
        } catch (Exception ex) {
            player.sendMessage(Text.mm("<red>That kit is unavailable right now. <gray>Try again later."));
            plugin.getLogger().fine("Kit claim failed for " + player.getName() + ": " + ex.getMessage());
        }
    }

    private YamlConfiguration nativeKits() {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "kits.yml");
        if (!file.exists()) plugin.saveResource("kits.yml", false);
        return YamlConfiguration.loadConfiguration(file);
    }

    private void openNative(Player player) {
        YamlConfiguration yml = nativeKits();
        ConfigurationSection section = yml.getConfigurationSection("kits");
        if (section == null) { player.sendMessage(Text.mm("<red>No native kits are configured.")); return; }
        Menu menu = new Menu(4, Text.mm("<dark_gray>Aetherfall · Native Kits"));
        int slot = 10;
        for (String name : section.getKeys(false)) {
            if (slot == 17 || slot == 26) slot += 2;
            ConfigurationSection kit = section.getConfigurationSection(name); if (kit == null) continue;
            boolean allowed = name.equalsIgnoreCase("starter") || player.hasPermission("aethercore.kits." + name) || player.hasPermission("essentials.kits." + name);
            long next = nativeNext(player, name);
            List<String> lore = new ArrayList<>(); lore.add("<gray>Contains <white>" + kit.getStringList("items").size() + " rewards"); lore.add("");
            lore.addAll(nativeContents(kit)); lore.add("");
            lore.add(!allowed ? "<red>✖ Locked" : next > System.currentTimeMillis() ? "<gold>⌛ Ready in " + Text.duration((next - System.currentTimeMillis()) / 1000) : "<green>✔ Ready to claim");
            lore.add("<aqua>▶ Right-click to preview");
            final boolean ready = allowed && next <= System.currentTimeMillis();
            menu.set(slot++, new ItemBuilder(icon(name)).name((ready ? "<green>" : "<dark_gray>") + "<bold>" + title(name)).lore(lore).glow(ready).build(), (p, click) -> {
                if (click.isRightClick()) openNativePreview(p, name); else if (ready) { claimNative(p, name); openNative(p); }
                else p.sendMessage(Text.mm(allowed ? "<gray>That kit is on cooldown." : "<gray>That kit is locked."));
            });
        }
        menu.set(31, new ItemBuilder(Material.BOOK).name("<gold><bold>Native kits").lore(List.of("<gray>EssentialsX is optional.", "<gray>Kit claims are validated and saved by AetherCore.")).build());
        menu.set(27, new ItemBuilder(Material.ARROW).name("<gray>← Back").build(), (p, c) -> plugin.menus().openMain(p));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(player);
    }

    private void openNativePreview(Player player, String name) {
        ConfigurationSection kit = nativeKits().getConfigurationSection("kits." + name);
        if (kit == null) { player.sendMessage(Text.mm("<red>That kit does not exist.")); return; }
        Menu menu = new Menu(5, Text.mm("<dark_gray>Native Kit Preview · " + title(name)));
        int slot = 0; for (String line : kit.getStringList("items")) { ItemStack item = nativeItem(player, line); if (item != null && slot < 36) menu.set(slot++, item); }
        boolean allowed = name.equalsIgnoreCase("starter") || player.hasPermission("aethercore.kits." + name) || player.hasPermission("essentials.kits." + name);
        boolean ready = allowed && nativeNext(player, name) <= System.currentTimeMillis();
        menu.set(36, new ItemBuilder(Material.ARROW).name("<gray>← Back to Kits").build(), (p, c) -> openNative(p));
        menu.set(40, new ItemBuilder(ready ? Material.EMERALD_BLOCK : Material.REDSTONE_BLOCK).name(ready ? "<green>Claim Kit" : "<red>Unavailable").build(), (p, c) -> { if (ready) { claimNative(p, name); openNative(p); } });
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(player);
    }

    private long nativeNext(Player player, String name) { Long value = player.getPersistentDataContainer().get(new org.bukkit.NamespacedKey(plugin, "native_kit_" + name.toLowerCase()), org.bukkit.persistence.PersistentDataType.LONG); return value == null ? 0L : value; }

    private void claimNative(Player player, String name) {
        ConfigurationSection kit = nativeKits().getConfigurationSection("kits." + name);
        if (kit == null) return;
        boolean allowed = name.equalsIgnoreCase("starter") || player.hasPermission("aethercore.kits." + name) || player.hasPermission("essentials.kits." + name);
        if (!allowed || nativeNext(player, name) > System.currentTimeMillis()) return;
        List<ItemStack> rewards = new ArrayList<>();
        long coins = 0L;
        for (String line : kit.getStringList("items")) {
            String[] parts = line.trim().split(" +");
            if (parts.length == 0 || parts[0].isBlank()) continue;
            if ((parts[0].equalsIgnoreCase("/aegive") || parts[0].equalsIgnoreCase("/aethergive") || parts[0].equalsIgnoreCase("/cgive")) && parts.length >= 3) {
                if (!plugin.items().isValidKey(parts[2])) continue;
                int amount = parseAmount(parts, 3);
                rewards.add(plugin.items().stack(parts[2], amount));
                continue;
            }
            if (parts[0].equalsIgnoreCase("/eco") && parts.length >= 4 && parts[1].equalsIgnoreCase("give")) {
                try { coins = Math.addExact(coins, Math.max(0L, Long.parseLong(parts[3]))); } catch (ArithmeticException | NumberFormatException ignored) { }
                continue;
            }
            if (parts[0].startsWith("/")) continue;
            ItemStack item = nativeItem(player, line);
            if (item != null) rewards.add(item);
        }
        // Simulate the complete item delivery against a copy of the storage inventory.
        org.bukkit.inventory.Inventory probe = Bukkit.createInventory(null, player.getInventory().getStorageContents().length);
        probe.setContents(player.getInventory().getStorageContents());
        Map<Integer, ItemStack> overflow = new java.util.HashMap<>();
        for (ItemStack reward : rewards) overflow.putAll(probe.addItem(reward.clone()));
        if (!overflow.isEmpty()) {
            player.sendMessage(Text.mm("<red>Your inventory does not have enough space for this kit."));
            return;
        }
        for (ItemStack reward : rewards) player.getInventory().addItem(reward);
        if (coins > 0) plugin.giveCoins(player, coins);
        long delay = Math.max(0L, kit.getLong("delay", 604800));
        player.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "native_kit_" + name.toLowerCase()), org.bukkit.persistence.PersistentDataType.LONG, System.currentTimeMillis() + delay * 1000L);
        player.sendMessage(Text.mm("<green>✔ Claimed the <aqua>" + title(name) + " <green>kit."));
    }

    private static int parseAmount(String[] parts, int index) {
        try { return Math.max(1, Math.min(64, Integer.parseInt(parts.length > index ? parts[index] : "1"))); }
        catch (NumberFormatException ignored) { return 1; }
    }

    private ItemStack nativeItem(Player player, String raw) {
        String[] parts = raw.trim().split(" +"); if (parts.length == 0 || parts[0].startsWith("/")) return null;
        Material material = Material.matchMaterial(parts[0]); if (material == null) return null;
        int amount = 1; try { if (parts.length > 1) amount = Math.max(1, Math.min(64, Integer.parseInt(parts[1]))); } catch (NumberFormatException ignored) { }
        return new ItemStack(material, amount);
    }

    private boolean nativeCommand(Player player, String raw) {
        String[] parts = raw.trim().split(" +"); if (parts.length == 0) return false;
        if ((parts[0].equalsIgnoreCase("/aegive") || parts[0].equalsIgnoreCase("/aethergive") || parts[0].equalsIgnoreCase("/cgive")) && parts.length >= 3) {
            String id = parts[2]; if (!plugin.items().isValidKey(id)) return true;
            int amount = 1; try { if (parts.length > 3) amount = Math.max(1, Math.min(64, Integer.parseInt(parts[3]))); } catch (NumberFormatException ignored) { }
            plugin.items().give(player, id, amount); return true;
        }
        if (parts[0].equalsIgnoreCase("/eco") && parts.length >= 4 && parts[1].equalsIgnoreCase("give")) {
            try { plugin.giveCoins(player, Math.max(0, Long.parseLong(parts[3]))); } catch (NumberFormatException ignored) { }
            return true;
        }
        // Native kits deliberately ignore arbitrary commands from the config.
        return parts[0].startsWith("/");
    }

    private List<String> nativeContents(ConfigurationSection kit) {
        List<String> result = new ArrayList<>(); for (String line : kit.getStringList("items")) { String[] p = line.trim().split(" +"); if (p.length > 0 && !p[0].startsWith("/")) result.add("<white>" + (p.length > 1 ? p[1] : "1") + "x " + title(p[0])); else if (p.length > 0 && p[0].equalsIgnoreCase("/aegive")) result.add("<aqua>Custom item reward"); else if (p.length > 0 && p[0].equalsIgnoreCase("/eco")) result.add("<gold>Coin reward"); } return result;
    }

    /** 0 = ready now, -1 = one-time kit already used, otherwise the epoch millis when it's ready again. */
    private long nextUse(Essentials ess, Player player, String name) {
        try {
            return new Kit(name, ess).getNextUse(ess.getUser(player));
        } catch (Exception e) {
            return 0;
        }
    }

    /** Human-readable kit contents from the Essentials item lines. */
    private List<String> contents(Kits kits, String name) {
        List<String> out = new ArrayList<>();
        Object raw = kits.getKit(name) == null ? null : kits.getKit(name).get("items");
        if (!(raw instanceof List<?> lines)) return out;
        for (Object o : lines) {
            String[] parts = String.valueOf(o).trim().split(" +");
            if (parts.length == 0 || parts[0].isBlank()) continue;
            String line;
            if (parts[0].equalsIgnoreCase("/eco") && parts.length >= 4) {
                try {
                    line = "<gold>" + Text.number(Long.parseLong(parts[3])) + " coins</gold>";
                } catch (Exception e) {
                    line = "<gold>" + parts[3] + " coins</gold>";
                }
            } else if ((parts[0].equalsIgnoreCase("/aegive") || parts[0].equalsIgnoreCase("/aethergive") || parts[0].equalsIgnoreCase("/cgive"))
                    && parts.length >= 3 && plugin.items().isValidKey(parts[2])) {
                String qty = parts.length > 3 ? parts[3] : "1";
                line = "<white>" + qty + "x </white>" + plugin.items().displayName(parts[2]);
            } else if (parts[0].equalsIgnoreCase("/aether") && parts.length >= 4 && parts[1].equalsIgnoreCase("give") && plugin.items().isValidKey(parts[3])) {
                String qty = parts.length > 4 ? parts[4] : "1";
                line = "<white>" + qty + "x </white>" + plugin.items().displayName(parts[3]);
            } else if (parts[0].startsWith("/")) {
                continue;
            } else {
                String amount = parts.length > 1 ? parts[1] : "1";
                String nice = java.util.Arrays.stream(parts[0].toLowerCase(java.util.Locale.ROOT).split("_"))
                        .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
                        .collect(java.util.stream.Collectors.joining(" "));
                line = "<white>" + amount + "x " + nice + (parts.length > 2 && !parts[2].startsWith("name:") && !parts[2].startsWith("lore:") ? " <aqua>✦" : "");
            }
            out.add("<dark_gray>• " + line);
        }
        return out;
    }

    private static String title(String raw) {
        if (raw == null || raw.isBlank()) return "Kit";
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1).replace('-', ' ');
    }

    private static String rank(String name) {
        return switch (name.toLowerCase()) {
            case "starter" -> "Newcomer";
            case "settler" -> "Settler";
            case "citizen" -> "Citizen";
            case "veteran" -> "Veteran";
            case "elder" -> "Elder";
            case "legend" -> "Legend";
            case "mythic" -> "Mythic";
            default -> "";
        };
    }

    private static Material icon(String name) {
        return switch (name.toLowerCase()) {
            case "starter" -> Material.WOODEN_PICKAXE;
            case "settler" -> Material.IRON_PICKAXE;
            case "citizen" -> Material.GOLDEN_PICKAXE;
            case "veteran" -> Material.DIAMOND_PICKAXE;
            case "elder" -> Material.EMERALD;
            case "legend" -> Material.NETHERITE_PICKAXE;
            case "mythic" -> Material.NETHER_STAR;
            default -> Material.CHEST;
        };
    }

    @Override public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (sender instanceof Player player) open(player);
        return true;
    }

    @Override public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        return List.of();
    }
}
