package com.combatbot;

import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.storm.api.domain.actors.IPlayer;
import net.storm.api.domain.items.IInventoryItem;
import net.storm.api.domain.tiles.ITileItem;
import net.storm.sdk.entities.Players;
import net.storm.sdk.entities.TileItems;
import net.storm.sdk.game.Skills;
import net.storm.sdk.items.Inventory;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Generieke helper: maakt 1 inventory-slot vrij door te eten zodat een loot-item
 * dat op de grond ligt opgepakt kan worden.
 *
 * Voorwaarden (alle moeten true zijn):
 *   - Config toggle {@link CombatBotConfig#eatToMakeSpaceForLoot()} staat aan
 *   - {@link Inventory#isFull()}
 *   - HP is NIET vol
 *   - Er is eetbaar food in de inventory ("Eat" of "Drink" actie)
 *   - Er ligt loot vlakbij (binnen radius) waarvan minstens 1 item GEEN bones/ashes is
 *     (zodat we niet onnodig food spillen voor goedkope bones die toch begraven kunnen worden)
 *
 * Werkt voor elke handler (Combat, Giants, Imps, Barb-loot, ...).
 *
 * @see CombatBotConfig#eatToMakeSpaceForLoot()
 */
final class EatForLootSpaceHelper {

    /** Items die we NIET als reden zien om food te spillen — die kunnen we toch buryen/scatteren. */
    private static final List<String> SKIP_LOOT_NAMES = Arrays.asList(
            "Bones",
            "Big bones",
            "Burnt bones",
            "Wolf bones",
            "Bat bones",
            "Babydragon bones",
            "Dragon bones",
            "Wyvern bones",
            "Wyrm bones",
            "Drake bones",
            "Hydra bones",
            "Lava dragon bones",
            "Frost dragon bones",
            "Ourg bones",
            "Superior dragon bones",
            "Zogre bones",
            "Jogre bones",
            "Fayrg bones",
            "Raurg bones",
            "Shaikahan bones",
            "Monkey bones",
            "Fiendish ashes",
            "Vile ashes",
            "Malicious ashes",
            "Abyssal ashes",
            "Infernal ashes"
    );

    private static final Random RNG = new Random();

    private EatForLootSpaceHelper() {}

    /**
     * Probeer 1 hap food te eten om plek te maken voor loot.
     *
     * @param config plugin-config (voor de toggle + ownership/lijst-keuzes door caller)
     * @param onlyOwnDrops als true → alleen "mine" tile-items tellen mee
     * @param radius zoekstraal in tiles vanaf de speler
     * @param wantedNames optioneel: lijst van loot-item namen die de caller per se wil pakken
     *                   (bijv. config.lootItems()/specialLootItems()). Mag leeg/null zijn — dan
     *                   tellen ALLE niet-bone/ash tile-items mee als "loot waar plek voor moet komen".
     * @param debugLog optionele callback voor debug-logs (mag null zijn)
     * @return delay in ms (>0 = food gegeten, geef terug aan handler), 0 = niets gedaan / niet nodig
     */
    static int tryEatForSpace(CombatBotConfig config,
                              boolean onlyOwnDrops,
                              int radius,
                              List<String> wantedNames,
                              java.util.function.Consumer<String> debugLog) {
        if (config == null) return 0;
        if (!config.eatToMakeSpaceForLoot()) return 0;
        if (!Inventory.isFull()) return 0;
        if (isHpFull()) return 0;

        IPlayer local = Players.getLocal();
        if (local == null) return 0;
        WorldPoint myPos = local.getWorldLocation();
        if (myPos == null) return 0;

        // 1) Vind food
        IInventoryItem food = Inventory.getFirst(item ->
                item != null && item.getName() != null
                        && (item.hasAction("Eat") || item.hasAction("Drink")));
        if (food == null) return 0;

        // 2) Vind een geldige loot-tile (niet bones/ashes) binnen radius
        ITileItem reason = findEatWorthyLootNearby(onlyOwnDrops, myPos, Math.max(0, radius), wantedNames);
        if (reason == null) return 0;

        // 3) Eet
        if (food.hasAction("Eat")) {
            food.interact("Eat");
        } else {
            food.interact("Drink");
        }
        if (debugLog != null) {
            String fn = safeName(food.getName());
            String ln = safeName(reason.getName());
            debugLog.accept("[EatForSpace] Inv vol + HP < max → eet " + fn
                    + " om plek te maken voor: " + ln);
        }
        return 900 + RNG.nextInt(500);
    }

    /** Versimpelde overload — geen wantedNames, radius 5, eigen drops alleen als caller dat wil. */
    static int tryEatForSpace(CombatBotConfig config,
                              boolean onlyOwnDrops,
                              java.util.function.Consumer<String> debugLog) {
        return tryEatForSpace(config, onlyOwnDrops, 6, null, debugLog);
    }

    /**
     * Zoek het dichtstbijzijnde tile-item binnen radius dat:
     *  - GEEN bones/ashes is (anders kunnen we beter buryen)
     *  - optioneel: matched met wantedNames (als die niet leeg is)
     *  - optioneel: alleen "mine" als onlyOwnDrops true is
     */
    private static ITileItem findEatWorthyLootNearby(boolean onlyOwnDrops,
                                                     WorldPoint myPos,
                                                     int radius,
                                                     List<String> wantedNames) {
        java.util.List<ITileItem> pool = onlyOwnDrops
                ? TileItems.getAllMine()
                : TileItems.getAll((int[]) null);
        if (pool == null || pool.isEmpty()) return null;

        ITileItem best = null;
        int bestDist = Integer.MAX_VALUE;
        boolean hasFilter = wantedNames != null && !wantedNames.isEmpty();

        for (ITileItem item : pool) {
            if (item == null || item.getName() == null || item.getWorldLocation() == null) continue;
            String n = item.getName();
            if (isBoneOrAsh(n)) continue;
            if (hasFilter) {
                boolean match = false;
                for (String w : wantedNames) {
                    if (w != null && w.equalsIgnoreCase(n)) { match = true; break; }
                }
                if (!match) continue;
            }
            int d = item.getWorldLocation().distanceTo(myPos);
            if (d > radius) continue;
            if (d < bestDist) {
                bestDist = d;
                best = item;
            }
        }
        return best;
    }

    static boolean isBoneOrAsh(String name) {
        if (name == null) return false;
        for (String s : SKIP_LOOT_NAMES) {
            if (s.equalsIgnoreCase(name)) return true;
        }
        // Generic guard: alles dat eindigt op " bones" of " ashes" en niet expliciet hierboven staat.
        String lower = name.toLowerCase();
        return lower.endsWith(" bones") || lower.endsWith(" ashes")
                || lower.equals("bones") || lower.equals("ashes");
    }

    private static boolean isHpFull() {
        try {
            int hp = Skills.getBoostedLevel(Skill.HITPOINTS);
            int max = Skills.getLevel(Skill.HITPOINTS);
            return hp >= max && max > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String safeName(String s) {
        return s == null ? "?" : s;
    }
}
