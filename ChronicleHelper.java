package com.combatbot;

import net.storm.api.domain.items.IInventoryItem;
import net.storm.sdk.entities.Players;
import net.storm.sdk.items.Bank;
import net.storm.sdk.items.Equipment;
import net.storm.sdk.items.Inventory;
import net.storm.api.domain.actors.IPlayer;

import java.util.Random;
import java.util.function.Predicate;

/**
 * Helper voor de Chronicle (Diango book) als Varrock-teleport vervanger.
 *
 * <p>Belangrijk: alle state wordt persistent bijgehouden in {@link AccountStateJsonStore} zodat
 * de bot tussen logins en zonder bank-trip weet of er een Chronicle is, hoeveel charges erop
 * staan en hoeveel losse Teleport cards in inv/bank liggen. State-updates gebeuren op:
 * <ul>
 *   <li>Bank-snapshot ({@link #syncFromBankSnapshot}).</li>
 *   <li>Inventory snapshot na bank-actie ({@link #syncCardsInInventory}).</li>
 *   <li>Teleport-trigger ({@link #teleportToVarrock}).</li>
 *   <li>Card-on-chronicle ({@link #chargeOneCardFromInventory}).</li>
 * </ul>
 */
public final class ChronicleHelper {

    /** Item-naam zoals OSRS spelt. */
    public static final String CHRONICLE = "Chronicle";
    /** OSRS Teleport card item naam (cards te koop bij o.a. Diango). */
    public static final String TELEPORT_CARD = "Teleport card";
    /** Maximaal charges dat één Chronicle kan houden (OSRS = 1000). */
    public static final int MAX_CHARGES = 1000;

    private static final Random RNG = new Random();

    private ChronicleHelper() {}

    private static void debug(String msg) {
        DebugLog.log("Chronicle", msg);
    }

    // -------------------------------------------------------------------------
    // Detectie (alleen kijken — geen bank/inv mutaties)
    // -------------------------------------------------------------------------

    /** {@code true} als de Chronicle in inventory zit. */
    public static boolean inInventory() {
        try {
            return Inventory.contains(CHRONICLE);
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code true} als de Chronicle equipped is. */
    public static boolean isEquipped() {
        try {
            return Equipment.contains(CHRONICLE);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Geeft losse Teleport cards in inventory terug. */
    public static int cardsInInventory() {
        try {
            return Math.max(0, Inventory.getCount(true, TELEPORT_CARD));
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Welk player-niveau is "rijk genoeg voor een grote card-batch"?
     * 100k coins is de drempel: dan kopen we 10–30 cards i.p.v. 3–10.
     * Coin-tellingen via inv (bank wordt apart in JSON gespiegeld).
     */
    public static int suggestedCardBatch(long approxCoins) {
        if (approxCoins >= 100_000L) {
            return 10 + RNG.nextInt(21); // 10..30
        }
        return 3 + RNG.nextInt(8); // 3..10
    }

    // -------------------------------------------------------------------------
    // State sync naar AccountStateJson
    // -------------------------------------------------------------------------

    /**
     * Bank-snapshot: roep aan met cards-in-bank + chronicle-aanwezig (true als chronicle ergens zichtbaar
     * is in bank/inv/equip). Dit is de hoofd-update: schrijft ook cardsInv mee.
     */
    public static void syncFromBankSnapshot(String displayName, int cardsInBank) {
        if (displayName == null || displayName.trim().isEmpty()) return;
        boolean owned = inInventory() || isEquipped() || (cardsInBank >= 0 && Bank.contains(CHRONICLE));
        AccountStateJsonStore.putChronicleState(
                displayName,
                owned,
                null,
                Math.max(0, cardsInBank),
                cardsInInventory());
    }

    /** Lichtere sync: alleen het cards-in-inv veld bijwerken (na withdraw/deposit/use). */
    public static void syncCardsInInventory(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) return;
        AccountStateJsonStore.putChronicleState(displayName, null, null, null, cardsInInventory());
    }

    // -------------------------------------------------------------------------
    // Acties
    // -------------------------------------------------------------------------

    /**
     * Teleporteer naar Varrock via Chronicle. Returns {@code true} als de "Teleport"-actie succesvol
     * gestart is. Charges worden persistent gedecrementeerd.
     *
     * <p>Inventory-pad is volledig ondersteund. Equipped chronicle wordt herkend (zie
     * {@link #isEquipped()}) maar de Teleport-actie via worn-equipment widget vereist platform-specifieke
     * widget-interacties die in de Resupply-fase (volgende ronde) worden toegevoegd. Voor nu wordt bij
     * een equipped-only state een log-tip uitgegeven en valt de caller terug op de spell/walk-flow.
     */
    public static boolean teleportToVarrock(String displayName) {
        if (inInventory()) {
            try {
                IInventoryItem item = Inventory.getFirst(CHRONICLE);
                if (item != null && itemHasAction(item, "Teleport")) {
                    item.interact("Teleport");
                    debug("teleportToVarrock: inv chronicle → Teleport");
                    AccountStateJsonStore.decrementChronicleCharges(displayName);
                    return true;
                }
                debug("teleportToVarrock: inv chronicle gevonden maar 'Teleport' actie ontbreekt (charges 0?)");
            } catch (Throwable t) {
                debug("teleportToVarrock inv: " + t.getMessage());
            }
            return false;
        }
        if (isEquipped()) {
            debug("teleportToVarrock: chronicle is equipped — worn-widget teleport komt in volgende update."
                    + " Tip: leg de chronicle in inventory zodat de bot 'm direct kan klikken.");
        } else {
            debug("teleportToVarrock: geen chronicle in inv/equip");
        }
        return false;
    }

    /**
     * Gebruik 1 Teleport card op de Chronicle in inventory om +1 charge toe te voegen. Returns {@code true}
     * als de combineer-actie succesvol gestart is. Vereist: chronicle in inv én cards in inv.
     */
    public static boolean chargeOneCardFromInventory(String displayName) {
        if (!inInventory()) {
            debug("chargeOneCardFromInventory: geen chronicle in inv");
            return false;
        }
        IInventoryItem card = Inventory.getFirst(TELEPORT_CARD);
        if (card == null) {
            debug("chargeOneCardFromInventory: geen card in inv");
            return false;
        }
        IInventoryItem book = Inventory.getFirst(CHRONICLE);
        if (book == null) {
            debug("chargeOneCardFromInventory: chronicle item niet vindbaar in inv");
            return false;
        }
        try {
            card.useOn(book);
            debug("chargeOneCardFromInventory: Teleport card USE-on Chronicle");
            AccountStateJsonStore.registerCardChargedFromInv(displayName);
            return true;
        } catch (Throwable t) {
            debug("chargeOneCardFromInventory: " + t.getMessage());
            return false;
        }
    }

    /**
     * Charge zoveel cards als in inv liggen (max {@code maxThisCall}). Tussen acties zit een korte
     * sleep zodat het serverside klikken niet te ratelijk lijkt. Returns aantal succesvolle charges.
     */
    public static int chargeAvailableCardsBatch(String displayName, int maxThisCall) {
        int n = Math.min(Math.max(0, maxThisCall), cardsInInventory());
        int ok = 0;
        for (int i = 0; i < n; i++) {
            if (!chargeOneCardFromInventory(displayName)) break;
            ok++;
            sleep(550 + RNG.nextInt(420));
        }
        debug("chargeAvailableCardsBatch: " + ok + "/" + n + " charges geladen");
        return ok;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean itemHasAction(IInventoryItem item, String wanted) {
        if (item == null || wanted == null) return false;
        try {
            // Belangrijkste API: hasAction. Hangt van SDK-versie af.
            return item.hasAction(wanted);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(Math.max(50, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Veiligheids-helper: lokaal kunnen we vertrouwen dat speler niet null is in een handler-flow. */
    static IPlayer localOrNull() {
        try { return Players.getLocal(); } catch (Throwable t) { return null; }
    }

    /** Predicate-style helper voor naam-match (case-insensitive). */
    static Predicate<IInventoryItem> nameEquals(String name) {
        final String n = name == null ? "" : name.toLowerCase();
        return item -> {
            if (item == null || item.getName() == null) return false;
            return item.getName().toLowerCase().equals(n);
        };
    }
}
