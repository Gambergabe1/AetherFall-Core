package gg.aetherfall.core.items;

import org.bukkit.Color;
import org.bukkit.Material;

import java.util.List;
import java.util.Map;

/** A custom item definition loaded from items.yml. */
public record CustomItem(
        String id,
        Material material,
        String name,
        Rarity rarity,
        Type type,
        List<String> lore,
        boolean glint,
        Map<String, Integer> enchants,
        Map<String, Double> attributes,
        boolean unbreakable,
        String ability,
        String abilityName,
        String abilityTrigger,
        List<String> abilityLore,
        String set,
        Map<String, Integer> effects,
        Map<String, Integer> recipe,
        int recipeAmount,
        double sell,
        Color color,
        String category) {

    public enum Type {
        RESOURCE, ENCHANTED, TOOL, WEAPON, BOW, HELMET, CHESTPLATE, LEGGINGS, BOOTS, TALISMAN, CONSUMABLE;

        public String label() {
            return switch (this) {
                case RESOURCE, ENCHANTED -> "MATERIAL";
                case WEAPON -> "SWORD";
                default -> name();
            };
        }

        public boolean isArmor() {
            return this == HELMET || this == CHESTPLATE || this == LEGGINGS || this == BOOTS;
        }
    }

    public enum Rarity {
        COMMON("<white>"), UNCOMMON("<green>"), RARE("<blue>"), EPIC("<dark_purple>"), LEGENDARY("<gold>"), MYTHIC("<light_purple>");

        public final String color;

        Rarity(String color) {
            this.color = color;
        }
    }

    public String coloredName() {
        return rarity.color + name;
    }

    /** Resources and enchanted materials are inert: no placing, eating, throwing or vanilla crafting. */
    public boolean inert() {
        return type == Type.RESOURCE || type == Type.ENCHANTED || type == Type.TALISMAN;
    }
}
