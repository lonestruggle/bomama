package com.combatbot;

import net.storm.api.domain.items.IInventoryItem;
import net.storm.sdk.items.Equipment;

import java.util.Locale;

/**
 * Ruimte in inventaris vóór Wield/Wear: melee wapen + shield → minstens 2 vrije slots
 * om een bow/staff/2h uit inventory aan te trekken.
 */
public final class InventoryEquipHelper {

    private InventoryEquipHelper() {
    }

    public static int freeSlots() {
        return BankInventoryHelper.freeSlots();
    }

    /** Hoeveel equipped items (wapen + shield) naar inv moeten bij wield van 2h/ranged/staff. */
    public static int minFreeSlotsToWield(String itemName) {
        if (itemName == null || itemName.trim().isEmpty()) {
            return 0;
        }
        if (!clearsShieldWhenWielding(itemName)) {
            return hasOccupiedWeaponSlot() ? 1 : 0;
        }
        return countEquippedWeaponAndShieldForSwap();
    }

    public static boolean hasFreeSlotsToWield(String itemName) {
        return freeSlots() >= minFreeSlotsToWield(itemName);
    }

    public static boolean hasFreeSlotsToWield(IInventoryItem item) {
        return item != null && hasFreeSlotsToWield(item.getName());
    }

    /**
     * Minimale vrije slots vóór style-gear equip (na bank-withdraw).
     * Ranged/Mage: huidig melee wapen + shield moeten in inv kunnen.
     */
    public static int minFreeSlotsForStyleEquip(CombatBotConfig.ImpsCombatStyle style) {
        if (style == null) {
            return 0;
        }
        switch (style) {
            case RANGED:
            case MAGE:
                return countEquippedWeaponAndShieldForSwap();
            case MELEE:
                if (hasTwoHandWeaponEquipped()) {
                    return 1;
                }
                return 0;
            default:
                return 0;
        }
    }

    public static int countEquippedWeaponAndShieldForSwap() {
        int n = 0;
        if (hasOccupiedWeaponSlot()) {
            n++;
        }
        if (hasOccupiedShieldSlot()) {
            n++;
        }
        return n;
    }

    public static boolean isLikelyWeaponItem(String itemName) {
        if (itemName == null) {
            return false;
        }
        String n = itemName.toLowerCase(Locale.ROOT);
        if (isShieldItemName(n)) {
            return false;
        }
        return n.contains("bow")
                || n.contains("crossbow")
                || n.contains("staff")
                || n.contains("wand")
                || n.contains("scimitar")
                || n.contains("sword")
                || n.contains("dagger")
                || n.contains("mace")
                || n.contains("axe")
                || n.contains("halberd")
                || n.contains("spear")
                || n.contains("whip")
                || n.contains("claw")
                || n.contains("blowpipe")
                || n.contains("halberd")
                || n.contains("2h");
    }

    public static boolean tryWieldOrWear(CombatBotConfig config, IInventoryItem item) {
        if (item == null || item.getName() == null) {
            return false;
        }
        int need = minFreeSlotsToWield(item.getName());
        int free = freeSlots();
        if (free < need) {
            DebugLog.log("Equip", "skip wield " + item.getName() + ": need " + need + " free slots, have " + free);
            return false;
        }
        String action = item.hasAction("Wield") ? "Wield"
                : (item.hasAction("Wear") ? "Wear" : null);
        if (action == null) {
            return false;
        }
        InventoryActionHelper.interact(config, item, action);
        return true;
    }

    /** Max wield-ruimte over alle weapon-requirements in één bank-sessie. */
    public static int maxWieldSlotsForRequirements(java.util.List<UniversalBankingManager.Requirement> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return 0;
        }
        int max = 0;
        for (UniversalBankingManager.Requirement r : requirements) {
            if (r == null) {
                continue;
            }
            String name = r.getItemName();
            if (name == null || name.isEmpty() || !isLikelyWeaponItem(name)) {
                continue;
            }
            max = Math.max(max, minFreeSlotsToWield(name));
        }
        return max;
    }

    private static boolean clearsShieldWhenWielding(String itemName) {
        String n = itemName.toLowerCase(Locale.ROOT);
        return n.contains("bow")
                || n.contains("crossbow")
                || n.contains("staff")
                || n.contains("2h")
                || n.contains(" halberd")
                || n.contains("spear")
                || n.contains("blowpipe");
    }

    private static boolean hasTwoHandWeaponEquipped() {
        try {
            return Equipment.contains(i -> i != null && i.getName() != null
                    && clearsShieldWhenWielding(i.getName()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean hasOccupiedWeaponSlot() {
        try {
            return Equipment.contains(i -> i != null && i.getName() != null
                    && isWeaponSlotItem(i.getName()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean hasOccupiedShieldSlot() {
        try {
            return Equipment.contains(i -> i != null && i.getName() != null
                    && isShieldItemName(i.getName().toLowerCase(Locale.ROOT)));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isWeaponSlotItem(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        if (isShieldItemName(n)) {
            return false;
        }
        if (n.contains("amulet") || n.contains("cape") || n.contains("cloak")
                || n.contains("ring") || n.contains("boot") || n.contains("glove")
                || n.contains("vambrace") || n.contains("bracer")
                || n.contains("helm") || n.contains("hat") || n.contains(" hood")
                || n.contains("platebody") || n.contains("chainbody") || n.contains("body")
                || n.contains("platelegs") || n.contains("chaps") || n.contains(" robe")
                || n.contains("arrow") || n.contains("bolt")) {
            return false;
        }
        return isLikelyWeaponItem(name);
    }

    private static boolean isShieldItemName(String n) {
        return n.contains("shield")
                || n.contains("defender")
                || n.contains("book of")
                || n.contains("tome of")
                || n.contains("buckler");
    }
}
