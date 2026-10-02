package gg.aetherfall.core.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Shared Hypixel-style layout for 6-row market screens. */
public final class Gui {
    private Gui() {}

    /** Left column: category tabs. */
    public static final int[] TABS = {0, 9, 18, 27, 36};

    /** The 6x4 content grid framed by coloured glass. */
    public static final int[] GRID = {
            11, 12, 13, 14, 15, 16,
            20, 21, 22, 23, 24, 25,
            29, 30, 31, 32, 33, 34,
            38, 39, 40, 41, 42, 43};

    public static ItemStack pane(Material m) {
        return new ItemBuilder(m).name(" ").build();
    }

    /** Fills every empty slot with the given pane (after content is placed). */
    public static void frame(Menu menu, Material pane) {
        menu.fill(pane);
    }
}
