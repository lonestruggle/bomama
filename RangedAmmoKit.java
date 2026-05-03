package com.combatbot;

import net.storm.api.domain.items.IInventoryItem;
import net.storm.api.widgets.Tab;
import net.storm.sdk.items.Equipment;
import net.storm.sdk.items.Inventory;
import net.storm.sdk.widgets.Tabs;

import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * Gedeelde ranged-ammo herkenning (naam + OSRS item-id) en equippen uit inventory.
 * Gebruikt door {@link CombatHandler} en {@link ImpsHandler}.
 */
public final class RangedAmmoKit {

    private RangedAmmoKit() {
    }

    public static final Object[][] LEVELED_AMMO = {
            {"Rune arrow", 40}, {"Rune bolt", 61},
            {"Adamant arrow", 30}, {"Adamant bolt", 46},
            {"Mithril arrow", 20}, {"Mithril bolt", 36},
            {"Steel arrow", 5}, {"Steel bolt", 31},
            {"Iron arrow", 1}, {"Iron bolt", 26},
            {"Bronze arrow", 1}, {"Bronze bolt", 1},
    };

    private static final String[] RANGED_AMMO_NAME_PARTS = {
            "arrow", "bolt", "dart", "javelin", "knife", "thrownaxe", "chinchompa", "brutal"
    };

    private static final int[][] LEVELED_AMMO_BASE_IDS = {
            {892, 893},
            {9144},
            {890, 891},
            {9143},
            {888, 889},
            {9142},
            {886, 887},
            {9141},
            {884, 885},
            {9140},
            {882, 883},
            {877, 878},
    };

    private static int[] extraLeveledRangedAmmoIdsForRow(int leveledAmmoRow) {
        switch (leveledAmmoRow) {
            case 3:
                return new int[]{9290, 9297, 9304};
            case 5:
                return new int[]{9289, 9296, 9303};
            case 7:
                return new int[]{9288, 9295, 9302};
            case 9:
                return new int[]{9287, 9294, 9301};
            default:
                return new int[]{};
        }
    }

    public static int leveledAmmoRowForItemId(int id) {
        for (int row = 0; row < LEVELED_AMMO_BASE_IDS.length; row++) {
            for (int bid : LEVELED_AMMO_BASE_IDS[row]) {
                if (bid == id) {
                    return row;
                }
            }
            for (int ex : extraLeveledRangedAmmoIdsForRow(row)) {
                if (ex == id) {
                    return row;
                }
            }
        }
        return -1;
    }

    public static int leveledRangedAmmoRequirementForItemId(int id) {
        int row = leveledAmmoRowForItemId(id);
        if (row < 0) {
            return -1;
        }
        return (int) LEVELED_AMMO[row][1];
    }

    public static boolean nameLooksLikeRangedAmmo(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("headless arrow") || n.contains("arrow shaft") || n.contains("ogre arrow shaft")) {
            return false;
        }
        for (String a : RANGED_AMMO_NAME_PARTS) {
            if (n.contains(a)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isProbablyNotedAmmoPlaceholder(IInventoryItem item) {
        if (item == null || item.getName() == null) {
            return true;
        }
        String n = item.getName().toLowerCase(Locale.ROOT);
        return n.startsWith("null") || n.contains("note:");
    }

    public static boolean inventoryItemIsUsableLeveledRangedAmmo(IInventoryItem item, int rangedLevel) {
        if (item == null || item.getQuantity() <= 0 || isProbablyNotedAmmoPlaceholder(item)) {
            return false;
        }
        int req = leveledRangedAmmoRequirementForItemId(item.getId());
        if (req >= 0) {
            return rangedLevel >= req;
        }
        return item.getName() != null && nameLooksLikeRangedAmmo(item.getName());
    }

    public static int leveledAmmoRankForInventoryName(String itemName) {
        if (itemName == null) {
            return 99999;
        }
        for (int i = 0; i < LEVELED_AMMO.length; i++) {
            if (itemName.equalsIgnoreCase((String) LEVELED_AMMO[i][0])) {
                return i;
            }
        }
        return 9000;
    }

    public static int getEquippedRangedAmmoQuantity() {
        try {
            return Equipment.getCount(true, item ->
                    item != null && (leveledAmmoRowForItemId(item.getId()) >= 0
                            || nameLooksLikeRangedAmmo(item.getName())));
        } catch (Exception e) {
            return 0;
        }
    }

    public static int getInventoryRangedAmmoQuantity(int rangedSkillLevel) {
        try {
            List<IInventoryItem> all = Inventory.getAll();
            if (all == null || all.isEmpty()) {
                return 0;
            }
            int sum = 0;
            for (IInventoryItem it : all) {
                if (inventoryItemIsUsableLeveledRangedAmmo(it, rangedSkillLevel)) {
                    sum += it.getQuantity();
                }
            }
            return sum;
        } catch (Exception e) {
            return 0;
        }
    }

    public static boolean inventoryHasUsableRangedAmmo(int rangedSkillLevel) {
        return Inventory.getFirst(item -> inventoryItemIsUsableLeveledRangedAmmo(item, rangedSkillLevel)) != null;
    }

    public static IInventoryItem findBestRangedAmmoInInventory(int rangedSkillLevel) {
        IInventoryItem best = null;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < LEVELED_AMMO.length; i++) {
            String name = (String) LEVELED_AMMO[i][0];
            int req = (int) LEVELED_AMMO[i][1];
            if (rangedSkillLevel < req) {
                continue;
            }
            IInventoryItem it = Inventory.getFirst(name);
            if (it != null && it.getQuantity() > 0 && !isProbablyNotedAmmoPlaceholder(it) && i < bestRank) {
                bestRank = i;
                best = it;
            }
            if (i < LEVELED_AMMO_BASE_IDS.length) {
                for (int bid : LEVELED_AMMO_BASE_IDS[i]) {
                    final int id = bid;
                    IInventoryItem byId = Inventory.getFirst(item ->
                            item != null && item.getId() == id && item.getQuantity() > 0);
                    if (byId != null && !isProbablyNotedAmmoPlaceholder(byId) && i < bestRank) {
                        bestRank = i;
                        best = byId;
                    }
                }
                for (int ex : extraLeveledRangedAmmoIdsForRow(i)) {
                    final int id = ex;
                    IInventoryItem byId = Inventory.getFirst(item ->
                            item != null && item.getId() == id && item.getQuantity() > 0);
                    if (byId != null && !isProbablyNotedAmmoPlaceholder(byId) && i < bestRank) {
                        bestRank = i;
                        best = byId;
                    }
                }
            }
        }
        if (best != null) {
            return best;
        }
        List<IInventoryItem> all = Inventory.getAll();
        if (all == null) {
            return null;
        }
        for (IInventoryItem item : all) {
            if (item == null || item.getName() == null || item.getQuantity() <= 0) {
                continue;
            }
            if (isProbablyNotedAmmoPlaceholder(item)) {
                continue;
            }
            int idRow = leveledAmmoRowForItemId(item.getId());
            int rank;
            if (idRow >= 0) {
                if (rangedSkillLevel < (int) LEVELED_AMMO[idRow][1]) {
                    continue;
                }
                rank = idRow;
            } else {
                if (!nameLooksLikeRangedAmmo(item.getName())) {
                    continue;
                }
                rank = leveledAmmoRankForInventoryName(item.getName());
            }
            if (rank < bestRank) {
                bestRank = rank;
                best = item;
            }
        }
        return best;
    }

    public static void interactToEquipRangedAmmo(IInventoryItem item) {
        if (item == null) {
            return;
        }
        if (item.hasAction("Wield")) {
            item.interact("Wield");
            return;
        }
        if (item.hasAction("Wear")) {
            item.interact("Wear");
            return;
        }
        if (item.hasAction("Equip")) {
            item.interact("Equip");
            return;
        }
        try {
            item.interact(0);
        } catch (Throwable ignored) {
            try {
                item.interact("Wield");
            } catch (Throwable ignored2) {
                item.interact("Equip");
            }
        }
    }

    /**
     * @param emptyQuiverGameMessageMs timestamp van laatste game message "geen ammo"
     * @param sleepMs                (min,max) sleeps in ms, zoals handler {@code sleep}
     * @return {@code null} = equip mislukt of geen stack; {@code ""} = al OK (quiver gevuld, geen actie);
     *         anders display naam van equipped stack
     */
    public static String tryEquipRangedAmmoFromInventoryReturningDisplayName(
            int rangedSkillLevel,
            long emptyQuiverGameMessageMs,
            BiConsumer<Integer, Integer> sleepMs,
            boolean toxicBlowpipeEquipped
    ) {
        if (toxicBlowpipeEquipped) {
            return "";
        }
        boolean recentEmpty = System.currentTimeMillis() - emptyQuiverGameMessageMs < 6000L;
        if (getEquippedRangedAmmoQuantity() > 0 && !recentEmpty) {
            return "";
        }
        try {
            Tabs.open(Tab.INVENTORY);
            sleepMs.accept(120, 280);
        } catch (Throwable ignored) {
        }
        IInventoryItem best = findBestRangedAmmoInInventory(rangedSkillLevel);
        if (best == null) {
            return null;
        }
        interactToEquipRangedAmmo(best);
        sleepMs.accept(850, 1400);
        if (getEquippedRangedAmmoQuantity() > 0) {
            String nm = best.getName();
            return nm != null ? nm : "";
        }
        return null;
    }

    /** Game messages die duiden op een lege quiver (Equipment API kan achterlopen). */
    public static boolean isRangedEmptyQuiverGameMessage(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("there is no ammo") || lower.contains("no ammo left")
                || lower.contains("you have no ammunition") || lower.contains("out of ammo");
    }
}
