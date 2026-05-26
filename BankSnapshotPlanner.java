package com.combatbot;

import net.storm.api.domain.actors.IPlayer;
import net.storm.sdk.entities.Players;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
/**
 * Beslist of de bot naar de bank moet lopen op basis van de persistente bank-snapshot
 * ({@link AccountStateJsonStore}), niet om "te kijken" of iets er ligt.
 * <p>Snapshot wordt bij elke bank-open geüpdatet ({@link BankSnapshotHelper}).</p>
 */
public final class BankSnapshotPlanner {

    private BankSnapshotPlanner() {
    }

    public static String currentDisplayName() {
        try {
            IPlayer lp = Players.getLocal();
            if (lp == null || lp.getName() == null) {
                return null;
            }
            return net.runelite.client.util.Text.removeTags(lp.getName()).trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean hasPersistedSnapshot(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return false;
        }
        try {
            AccountStateJsonStore.AccountEntry e = AccountStateJsonStore.getEntry(displayName);
            return e != null && e.knownBankItemQtyJson != null && !e.knownBankItemQtyJson.trim().isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Bank-coins: max van qty-JSON ({@code "Coins"}) en {@link AccountStateJsonStore.AccountEntry#knownBankCoins}.
     * De JSON-map mist "Coins" soms terwijl {@code knownBankCoins} wél gezet is bij bank-open.
     */
    public static long knownBankCoinsQty(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return 0L;
        }
        long best = 0L;
        try {
            best = Math.max(best, (long) knownBankQty(displayName, "Coins"));
            AccountStateJsonStore.AccountEntry e = AccountStateJsonStore.getEntry(displayName);
            if (e != null) {
                best = Math.max(best, Math.max(0L, e.knownBankCoins));
            }
        } catch (Throwable ignored) {
        }
        return best;
    }

    /**
     * Vóór GE-restock: eerst bank voor coins als inv te laag is en snapshot bank-gp suggereert.
     */
    public static boolean shouldWithdrawBankCoinsBeforeGe(String displayName, int minInvCoins) {
        if (minInvCoins <= 0) {
            return false;
        }
        if (displayName == null || displayName.isEmpty()) {
            return true;
        }
        if (!hasPersistedSnapshot(displayName)) {
            return true;
        }
        long bankCoins = knownBankCoinsQty(displayName);
        if (bankCoins > 0L) {
            DebugLog.log("BankSnapshot", "GE: bank snapshot ~" + bankCoins + " gp → eerst ophalen");
            return true;
        }
        try {
            AccountStateJsonStore.AccountEntry e = AccountStateJsonStore.getEntry(displayName);
            if (e != null && AccountStateJsonStore.knownCoinsApprox(e) > 0L) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Hoeveelheid in laatste bank-snapshot (0 als item niet in JSON staat). */
    public static int knownBankQty(String displayName, String itemName) {
        if (displayName == null || displayName.isEmpty() || itemName == null || itemName.isEmpty()) {
            return 0;
        }
        try {
            AccountStateJsonStore.AccountEntry e = AccountStateJsonStore.getEntry(displayName);
            return AccountStateJsonStore.knownBankQty(e, itemName);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * Snapshot bestaat én item staat expliciet op 0 (of ontbreekt in qty-map → 0).
     * Zonder snapshot: {@code false} (bank bezoeken om te kalibreren mag nog).
     */
    public static boolean snapshotConfirmsAbsent(String displayName, String itemName) {
        if (!hasPersistedSnapshot(displayName)) {
            return false;
        }
        return knownBankQty(displayName, itemName) <= 0;
    }

    /**
     * Naar bank lopen om {@code itemName} op te halen?
     * {@code true} = snapshot zegt voorraad &gt; 0, of geen snapshot (nog kalibreren).
     */
    public static boolean shouldWalkToBankForWithdraw(String displayName, String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return true;
        }
        if (!hasPersistedSnapshot(displayName)) {
            return true;
        }
        return knownBankQty(displayName, itemName) > 0;
    }

    public static boolean shouldWalkToBankForWithdraw(String displayName, String... itemNames) {
        if (itemNames == null || itemNames.length == 0) {
            return true;
        }
        for (String name : itemNames) {
            if (shouldWalkToBankForWithdraw(displayName, name)) {
                return true;
            }
        }
        if (!hasPersistedSnapshot(displayName)) {
            return true;
        }
        DebugLog.log("BankSnapshot", "skip bank walk: snapshot leeg voor "
                + Arrays.toString(itemNames) + " (" + displayName + ")");
        return false;
    }

    /**
     * UBM/requirements: bank alleen als minstens één tekort-item volgens snapshot in de bank kan liggen,
     * of er nog geen snapshot is.
     */
    public static boolean shouldWalkToBankForRequirements(String displayName,
            List<UniversalBankingManager.Requirement> requirements,
            java.util.function.ToIntFunction<String> invCount) {
        if (requirements == null || requirements.isEmpty()) {
            return false;
        }
        if (!hasPersistedSnapshot(displayName)) {
            return true;
        }
        for (UniversalBankingManager.Requirement r : requirements) {
            if (r == null) {
                continue;
            }
            String name = r.getItemName();
            if (name == null || name.isEmpty()) {
                continue;
            }
            int have = invCount != null ? invCount.applyAsInt(name) : 0;
            if (have >= r.getMinAmount()) {
                continue;
            }
            if (shouldWalkToBankForWithdraw(displayName, name)) {
                return true;
            }
        }
        DebugLog.log("BankSnapshot", "skip bank walk: alle tekorten bevestigd afwezig in snapshot ("
                + displayName + ")");
        return false;
    }

    /** Namen die nog tekort zijn t.o.v. minAmount (voor GE-direct). */
    public static List<String> itemsShortInInv(List<UniversalBankingManager.Requirement> requirements,
            java.util.function.ToIntFunction<String> invCount) {
        List<String> out = new ArrayList<>();
        if (requirements == null) {
            return out;
        }
        for (UniversalBankingManager.Requirement r : requirements) {
            if (r == null) {
                continue;
            }
            String name = r.getItemName();
            if (name == null || name.isEmpty()) {
                continue;
            }
            int have = invCount != null ? invCount.applyAsInt(name) : 0;
            if (have < r.getMinAmount()) {
                out.add(name);
            }
        }
        return out;
    }

    /** Eerste item dat volgens snapshot niet in bank zit en nog onder minimum in inv is. */
    public static String firstSnapshotAbsentShortage(String displayName,
            List<UniversalBankingManager.Requirement> requirements,
            java.util.function.ToIntFunction<String> invCount) {
        for (String name : itemsShortInInv(requirements, invCount)) {
            if (snapshotConfirmsAbsent(displayName, name)) {
                return name;
            }
        }
        return null;
    }

    public static String formatSnapshotHint(String displayName, String itemName) {
        if (!hasPersistedSnapshot(displayName)) {
            return "geen snapshot";
        }
        int q = knownBankQty(displayName, itemName);
        return "bank≈" + q;
    }
}
