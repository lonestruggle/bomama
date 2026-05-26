package com.combatbot;

import java.util.Locale;

/**
 * Melee / ranged / mage armor & wapen via equipment-snapshot + live {@link net.storm.sdk.items.Equipment}.
 * Slots: HEAD, BODY, LEGS, GLOVES, BOOTS, WEAPON, SHIELD, AMULET, CAPE, AMMO.
 */
public final class EquipmentStylePlanner {

    public enum ArmorStyle {
        MELEE,
        RANGED,
        MAGE,
        UNKNOWN
    }

    public static final String SLOT_HEAD = "HEAD";
    public static final String SLOT_BODY = "BODY";
    public static final String SLOT_LEGS = "LEGS";
    public static final String SLOT_WEAPON = "WEAPON";
    public static final String SLOT_SHIELD = "SHIELD";

    private EquipmentStylePlanner() {
    }

    /** Hoofd + torso + benen gevuld met armor die bij {@code style} past (snapshot of live). */
    public static boolean hasCoreArmorForStyle(String displayName, ArmorStyle style) {
        if (style == null || style == ArmorStyle.UNKNOWN) {
            return false;
        }
        return slotMatchesStyle(displayName, SLOT_HEAD, style)
                && slotMatchesStyle(displayName, SLOT_BODY, style)
                && slotMatchesStyle(displayName, SLOT_LEGS, style);
    }

    /** Minstens één armor-slot (HEAD/BODY/LEGS) bezet. */
    public static boolean hasAnyArmorEquipped(String displayName) {
        return isSlotFilled(displayName, SLOT_HEAD)
                || isSlotFilled(displayName, SLOT_BODY)
                || isSlotFilled(displayName, SLOT_LEGS);
    }

    public static boolean isSlotFilled(String displayName, String slotName) {
        EquippedSlotSnapshot row = EquipmentSnapshotPlanner.itemInSlot(displayName, slotName);
        return row != null && row.name != null && !row.name.isEmpty();
    }

    public static boolean slotMatchesStyle(String displayName, String slotName, ArmorStyle style) {
        EquippedSlotSnapshot row = EquipmentSnapshotPlanner.itemInSlot(displayName, slotName);
        if (row == null || row.name == null || row.name.isEmpty()) {
            return false;
        }
        return itemNameMatchesStyle(row.name, style);
    }

    /**
     * Stijl afleiden uit WEAPON + BODY (snapshot/live).
     */
    public static ArmorStyle inferStyleFromEquipped(String displayName) {
        ArmorStyle fromWeapon = styleFromItemName(slotItemName(displayName, SLOT_WEAPON));
        if (fromWeapon != ArmorStyle.UNKNOWN) {
            return fromWeapon;
        }
        ArmorStyle fromBody = styleFromItemName(slotItemName(displayName, SLOT_BODY));
        if (fromBody != ArmorStyle.UNKNOWN) {
            return fromBody;
        }
        if (slotMatchesStyle(displayName, SLOT_HEAD, ArmorStyle.MAGE)
                || EquipmentSnapshotPlanner.slotContainsNamePart(displayName, SLOT_WEAPON, "staff")) {
            return ArmorStyle.MAGE;
        }
        if (hasCoreArmorForStyle(displayName, ArmorStyle.RANGED)) {
            return ArmorStyle.RANGED;
        }
        if (hasCoreArmorForStyle(displayName, ArmorStyle.MELEE)) {
            return ArmorStyle.MELEE;
        }
        return ArmorStyle.UNKNOWN;
    }

    public static String slotItemName(String displayName, String slotName) {
        EquippedSlotSnapshot row = EquipmentSnapshotPlanner.itemInSlot(displayName, slotName);
        return row != null && row.name != null ? row.name : "";
    }

    /**
     * Bank-trip voor armor in dit slot nodig? {@code false} als slot al goede armor heeft of snapshot+bank leeg.
     */
    public static boolean shouldBankForArmorSlot(String displayName, String slotName, ArmorStyle style,
                                                String[] candidateItemNames) {
        if (slotMatchesStyle(displayName, slotName, style)) {
            return false;
        }
        if (candidateItemNames == null || candidateItemNames.length == 0) {
            return isSlotFilled(displayName, slotName);
        }
        for (String item : candidateItemNames) {
            if (item != null && BankSnapshotPlanner.shouldWalkToBankForWithdraw(displayName, item)) {
                return true;
            }
        }
        return false;
    }

    /** Itemnaam staat in snapshot op een armor-slot (HEAD/BODY/LEGS) en matcht één van de gegeven namen. */
    public static boolean snapshotOrLiveWearsAny(String displayName, String... exactNames) {
        if (exactNames == null) {
            return false;
        }
        for (String name : exactNames) {
            if (name == null || name.isEmpty()) {
                continue;
            }
            if (EquipmentSnapshotPlanner.hasEquippedItem(displayName, name)) {
                return true;
            }
        }
        return false;
    }

    /** Laagste index in {@code slotOrder} (beste eerst) voor een ranged-stuk in snapshot armor-slots. */
    public static int bestRangedRankFromSnapshot(String displayName, String[] slotOrder) {
        if (slotOrder == null || slotOrder.length == 0) {
            return Integer.MAX_VALUE;
        }
        int best = Integer.MAX_VALUE;
        for (EquippedSlotSnapshot row : EquipmentSnapshotPlanner.knownEquippedSlots(displayName)) {
            if (row.name == null || row.slot == null) {
                continue;
            }
            String slot = row.slot.toUpperCase(Locale.ROOT);
            if (!SLOT_HEAD.equals(slot) && !SLOT_BODY.equals(slot) && !SLOT_LEGS.equals(slot)
                    && !"GLOVES".equals(slot) && !"CAPE".equals(slot)) {
                continue;
            }
            for (int i = 0; i < slotOrder.length; i++) {
                if (slotOrder[i] != null && slotOrder[i].equalsIgnoreCase(row.name)) {
                    best = Math.min(best, i);
                }
            }
        }
        return best;
    }

    /** Hoogste melee-tier index in snapshot armor-slots voor gegeven namen (zelfde volgorde als Imps tier-lijst). */
    public static int bestMeleeTierFromSnapshot(String displayName, String[] orderedBestFirst,
                                                java.util.function.Function<String, Integer> tierFn) {
        if (orderedBestFirst == null || tierFn == null) {
            return -1;
        }
        int best = -1;
        for (EquippedSlotSnapshot row : EquipmentSnapshotPlanner.knownEquippedSlots(displayName)) {
            if (row.name == null || row.slot == null) {
                continue;
            }
            String slot = row.slot.toUpperCase(Locale.ROOT);
            if (!SLOT_HEAD.equals(slot) && !SLOT_BODY.equals(slot) && !SLOT_LEGS.equals(slot)
                    && !SLOT_SHIELD.equals(slot)) {
                continue;
            }
            for (String candidate : orderedBestFirst) {
                if (candidate != null && candidate.equalsIgnoreCase(row.name)) {
                    best = Math.max(best, tierFn.apply(candidate));
                }
            }
        }
        return best;
    }

    public static boolean itemNameMatchesStyle(String itemName, ArmorStyle style) {
        if (itemName == null || itemName.isEmpty() || style == null) {
            return false;
        }
        switch (style) {
            case MELEE:
                return isMeleeArmorName(itemName);
            case RANGED:
                return isRangedArmorName(itemName);
            case MAGE:
                return isMageArmorName(itemName);
            default:
                return false;
        }
    }

    public static ArmorStyle styleFromItemName(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return ArmorStyle.UNKNOWN;
        }
        String n = itemName.toLowerCase(Locale.ROOT);
        if (n.contains("staff") || n.contains("wand")) {
            return ArmorStyle.MAGE;
        }
        if (n.contains("bow") || n.contains("blowpipe")) {
            return ArmorStyle.RANGED;
        }
        if (n.contains("arrow") || n.contains("bolt")) {
            return ArmorStyle.RANGED;
        }
        if (isMageArmorName(itemName)) {
            return ArmorStyle.MAGE;
        }
        if (isRangedArmorName(itemName)) {
            return ArmorStyle.RANGED;
        }
        if (isMeleeArmorName(itemName)) {
            return ArmorStyle.MELEE;
        }
        if (n.contains("scimitar") || n.contains("sword") || n.contains("dagger")
                || n.contains("mace") || n.contains("battleaxe") || n.contains("longsword")) {
            return ArmorStyle.MELEE;
        }
        return ArmorStyle.UNKNOWN;
    }

    private static boolean isMeleeArmorName(String itemName) {
        String n = itemName.toLowerCase(Locale.ROOT);
        return n.contains("platebody") || n.contains("platelegs") || n.contains("plateskirt")
                || n.contains("chainbody") || n.contains("full helm") || n.contains("med helm")
                || n.contains("kiteshield") || n.contains("sq shield")
                || (n.contains("plate") && !n.contains("d'hide"));
    }

    private static boolean isRangedArmorName(String itemName) {
        String n = itemName.toLowerCase(Locale.ROOT);
        return n.contains("d'hide") || n.contains("leather") || n.contains("coif")
                || n.contains("studded") || n.contains("vambraces") || n.contains("chaps")
                || n.contains("cowl");
    }

    private static boolean isMageArmorName(String itemName) {
        String n = itemName.toLowerCase(Locale.ROOT);
        return n.contains("wizard") || n.contains("robe") || n.contains("mystic")
                || n.contains("monk's");
    }
}
