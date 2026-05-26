package com.combatbot;

import net.storm.api.domain.items.IInventoryItem;
import net.storm.sdk.items.Inventory;

import java.util.ArrayList;
import java.util.List;
/**
 * Referentie voor Treasure Trail clue scrolls — geen volledige solver (duizenden unieke stappen).
 * <p>
 * <b>Item-ID:</b> elke tier heeft een eigen scroll-item (easy/medium/hard/…).
 * <b>Stappen:</b> geen apart item-ID per stap; voortgang zit in client-varbits + clue-tekst in de interface.
 */
public final class ClueScrollHelper {

    public enum Tier {
        BEGINNER(23182, "beginner", 1, 3),
        EASY(2677, "easy", 2, 4),
        MEDIUM(2801, "medium", 3, 5),
        HARD(2722, "hard", 4, 6),
        ELITE(12073, "elite", 5, 7),
        MASTER(19835, "master", 5, 8);

        public final int itemId;
        public final String label;
        public final int minSteps;
        public final int maxSteps;

        Tier(int itemId, String label, int minSteps, int maxSteps) {
            this.itemId = itemId;
            this.label = label;
            this.minSteps = minSteps;
            this.maxSteps = maxSteps;
        }
    }

    private ClueScrollHelper() {
    }

    public static Tier tierForItemId(int itemId) {
        for (Tier t : Tier.values()) {
            if (t.itemId == itemId) {
                return t;
            }
        }
        return null;
    }

    public static String tierLabelForItemId(int itemId) {
        Tier t = tierForItemId(itemId);
        return t != null ? t.label : null;
    }

    public static boolean isClueScrollItemId(int itemId) {
        return tierForItemId(itemId) != null;
    }

    /** Alle clue scrolls in inventory (Storm SDK). */
    public static List<ClueInInventory> findCluesInInventory() {
        List<ClueInInventory> out = new ArrayList<>();
        try {
            for (IInventoryItem item : Inventory.getAll()) {
                if (item == null) {
                    continue;
                }
                Tier tier = tierForItemId(item.getId());
                if (tier == null) {
                    continue;
                }
                String name = item.getName();
                out.add(new ClueInInventory(tier, item.getId(), name, item.getQuantity()));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Log naar Debug-tab — handig bij voorbereiden van een solver. */
    public static void logInventoryCluesToDebug() {
        List<ClueInInventory> clues = findCluesInInventory();
        if (clues.isEmpty()) {
            DebugLog.log("Clue", "Geen clue scroll in inventory (bekende tier-IDs: "
                    + "beginner=23182 easy=2677 medium=2801 hard=2722 elite=12073 master=19835)");
            return;
        }
        for (ClueInInventory c : clues) {
            DebugLog.log("Clue", "Inventory: id=" + c.itemId + " tier=" + c.tier.label
                    + " qty=" + c.quantity
                    + (c.name != null && !c.name.isEmpty() ? " name=\"" + c.name + "\"" : "")
                    + " | stappen: geen apart item-ID — lees clue-tekst / Check steps in-game");
            if (c.tier == Tier.BEGINNER) {
                DebugLog.log("Clue", "Beginner DB: " + BeginnerClueReference.allEntries().length
                        + " bekende stappen — Debug-knop 'Beginner clue DB' voor volledige lijst");
            }
        }
        DebugLog.log("Clue", "Solver: match clue-tekst (BeginnerClueReference.matchByClueText) → walk/talk/emote/dig.");
    }

    /** Dump volledige beginner-clue-database (Debug-tab, bron Clue). */
    public static void logBeginnerReferenceToDebug() {
        BeginnerClueReference.logFullReferenceToDebug();
    }

    public static final class ClueInInventory {
        public final Tier tier;
        public final int itemId;
        public final String name;
        public final int quantity;

        ClueInInventory(Tier tier, int itemId, String name, int quantity) {
            this.tier = tier;
            this.itemId = itemId;
            this.name = name;
            this.quantity = quantity;
        }
    }

    /** Korte uitleg voor panel-tooltip / documentatie. */
    public static String solverFeasibilitySummary() {
        return "<html><b>Clue scroll IDs</b><br>"
                + "Per <i>tier</i> één item-ID (beginner/easy/medium/hard/elite/master).<br>"
                + "Elke <i>stap</i> heeft <b>geen</b> eigen item-ID — de tekst in het clue-venster "
                + "bepaalt wat je moet doen (emote, coördinaat, puzzel, …).<br><br>"
                + "Een bot-solver heeft een grote clue-database nodig (zoals RuneLite Clue Scroll-plugin) "
                + "plus walk/interact/STASH-logica per cluetype."
                + "</html>";
    }
}
