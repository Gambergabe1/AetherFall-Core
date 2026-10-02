package gg.aetherfall.core.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Locale;

/** Thin wrapper over the Vault economy (EssentialsX provides it). */
@SuppressWarnings("deprecation")
public final class Eco {
    private Eco() {}

    private static Economy economy() {
        var rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    public static boolean available() {
        return economy() != null;
    }

    public static double balance(OfflinePlayer p) {
        Economy e = economy();
        return e == null ? 0 : e.getBalance(p);
    }

    public static boolean has(OfflinePlayer p, double amount) {
        Economy e = economy();
        return e != null && e.has(p, round(amount));
    }

    public static boolean withdraw(OfflinePlayer p, double amount) {
        Economy e = economy();
        if (e == null || amount < 0) return false;
        if (amount == 0) return true;
        return e.withdrawPlayer(p, round(amount)).transactionSuccess();
    }

    public static boolean deposit(OfflinePlayer p, double amount) {
        Economy e = economy();
        if (e == null || amount < 0) return false;
        if (amount == 0) return true;
        return e.depositPlayer(p, round(amount)).transactionSuccess();
    }

    public static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** 1234.5 -> "1,234.5", 12 -> "12". */
    public static String fmt(double v) {
        double r = round(v);
        return r == Math.floor(r) ? String.format(Locale.ROOT, "%,d", (long) r) : String.format(Locale.ROOT, "%,.1f", r);
    }
}
