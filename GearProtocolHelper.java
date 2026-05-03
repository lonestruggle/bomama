package com.combatbot;

import net.runelite.api.coords.WorldPoint;
import net.runelite.api.Skill;
import net.storm.sdk.game.Skills;
import net.storm.sdk.items.Bank;
import net.storm.sdk.items.Inventory;

/**
 * Gedeelde logica voor het Universele Start- en Bank-Protocol.
 * - Route naar bank: altijd via BankHelper (exclusief Cooking & Crafting Guild).
 * - Bij ontbrekende items: coins + Varrock-teleport runes (Magic 25+) uit bank, dan naar GE.
 */
public final class GearProtocolHelper {

    private GearProtocolHelper() {}

    /** Grand Exchange locatie (gedeeld door alle skills voor GE-restock). */
    public static final WorldPoint GE_LOCATION = new WorldPoint(3164, 3487, 0);

    /** Varrock teleport: 1 Law, 3 Air, 1 Fire per cast. We pakken iets meer voor meerdere teleports. */
    private static final int LAW_RUNES_FOR_GE = 10;
    private static final int AIR_RUNES_FOR_GE = 15;
    private static final int FIRE_RUNES_FOR_GE = 5;
    private static final int MAGIC_LEVEL_VARROCK_TELEPORT = 25;

    /**
     * Scan: zorg dat we weten wat we vasthebben.
     * De Storm Inventory-API leest de huidige inventaris; er is geen aparte "tab openen" nodig
     * voor een correcte lezing. Indien de SDK later Tabs.open(INVENTORY) biedt, kan dat hier.
     */
    public static void ensureInventoryScanned() {
        // Inventory.getAll() / Inventory.contains() gebruiken is voldoende; geen tab-actie nodig.
    }

    /**
     * Trek coins en (bij Magic 25+) Varrock-teleport runes uit de bank voor een GE-trip.
     * Gebruikt nu de 4-fase VarrockTeleportHelper voor de teleport puzzel.
     *
     * @param minCoins Minimum aantal coins om mee te nemen (bijv. 50000 voor tool/bait).
     */
    public static void withdrawCoinsAndVarrockRunesForGe(int minCoins) {
        if (!Bank.isOpen()) return;

        int coins = getItemQuantity("Coins");
        if (coins < minCoins && Bank.contains("Coins")) {
            int need = Math.min(minCoins - coins, 500_000);
            Bank.withdraw("Coins", need);
        }

        // Gebruik de 4-fase Varrock Teleport puzzel
        VarrockTeleportHelper.prepareVarrockTeleport();
    }

    private static int getItemQuantity(String name) {
        if (name == null || name.isEmpty()) return 0;
        var item = Inventory.getFirst(name);
        return item != null ? item.getQuantity() : 0;
    }

    /**
     * Controleer of we Magic 25+ hebben voor Varrock teleport naar GE.
     */
    public static boolean canUseVarrockTeleportForGe() {
        try {
            return Skills.getLevel(Skill.MAGIC) >= MAGIC_LEVEL_VARROCK_TELEPORT;
        } catch (Exception e) {
            return false;
        }
    }
}
