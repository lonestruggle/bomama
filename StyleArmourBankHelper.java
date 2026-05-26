package com.combatbot;

import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.storm.api.domain.items.IInventoryItem;
import net.storm.sdk.game.Skills;
import net.storm.sdk.items.Bank;
import net.storm.sdk.items.Equipment;
import net.storm.sdk.items.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bank gear-prep: per combat style (melee/ranged/mage) per slot het beste stuk zoeken
 * dat in de bank ligt én waarvan Defence/Ranged/Magic + quest (bv. Dragon Slayer I) OK is, ophalen en aantrekken.
 */
public final class StyleArmourBankHelper {

    /** OSRS: rune platebody + green d'hide body vereisen Dragon Slayer I. */
    private static final Quest DRAGON_SLAYER_BODY_QUEST = Quest.DRAGON_SLAYER_I;

    @FunctionalInterface
    public interface SleepCallback {
        void sleep(int minMs, int maxMs);
    }

    public static final class ArmourAction {
        public final String slot;
        public final String itemName;
        public final boolean withdraw;

        public ArmourAction(String slot, String itemName, boolean withdraw) {
            this.slot = slot;
            this.itemName = itemName;
            this.withdraw = withdraw;
        }
    }

    private static final class GearPiece {
        final String slot;
        final String name;
        final int defenseReq;
        final int rangedReq;
        final int magicReq;
        final Quest requiredQuest;

        GearPiece(String slot, String name, int defenseReq, int rangedReq, int magicReq, Quest requiredQuest) {
            this.slot = slot;
            this.name = name;
            this.defenseReq = defenseReq;
            this.rangedReq = rangedReq;
            this.magicReq = magicReq;
            this.requiredQuest = requiredQuest;
        }
    }

    private static GearPiece gp(String slot, String name, int def, int range, int magic) {
        return new GearPiece(slot, name, def, range, magic, null);
    }

    private static GearPiece gpQuest(String slot, String name, int def, int range, int magic, Quest quest) {
        return new GearPiece(slot, name, def, range, magic, quest);
    }

    /** Best → worst per slot (alleen armor, geen wapen/ammo). */
    private static final GearPiece[] MELEE_ARMOUR_TIER = {
            gp("HEAD", "Rune full helm", 40, 0, 0), gp("HEAD", "Rune med helm", 40, 0, 0),
            gp("HEAD", "Adamant full helm", 30, 0, 0), gp("HEAD", "Adamant med helm", 30, 0, 0),
            gp("HEAD", "Mithril full helm", 20, 0, 0), gp("HEAD", "Mithril med helm", 20, 0, 0),
            gp("HEAD", "Black full helm", 10, 0, 0), gp("HEAD", "Steel full helm", 5, 0, 0),
            gp("HEAD", "Steel med helm", 5, 0, 0), gp("HEAD", "Iron full helm", 1, 0, 0),
            gp("HEAD", "Bronze full helm", 1, 0, 0),

            gp("BODY", "Rune chainbody", 40, 0, 0),
            gpQuest("BODY", "Rune platebody", 40, 0, 0, DRAGON_SLAYER_BODY_QUEST),
            gp("BODY", "Adamant platebody", 30, 0, 0), gp("BODY", "Adamant chainbody", 30, 0, 0),
            gp("BODY", "Mithril platebody", 20, 0, 0), gp("BODY", "Mithril chainbody", 20, 0, 0),
            gp("BODY", "Black platebody", 10, 0, 0), gp("BODY", "Steel platebody", 5, 0, 0),
            gp("BODY", "Steel chainbody", 5, 0, 0), gp("BODY", "Iron platebody", 1, 0, 0),
            gp("BODY", "Bronze platebody", 1, 0, 0),

            gp("LEGS", "Rune platelegs", 40, 0, 0), gp("LEGS", "Rune plateskirt", 40, 0, 0),
            gp("LEGS", "Adamant platelegs", 30, 0, 0), gp("LEGS", "Adamant plateskirt", 30, 0, 0),
            gp("LEGS", "Mithril platelegs", 20, 0, 0), gp("LEGS", "Mithril plateskirt", 20, 0, 0),
            gp("LEGS", "Black platelegs", 10, 0, 0), gp("LEGS", "Steel platelegs", 5, 0, 0),
            gp("LEGS", "Iron platelegs", 1, 0, 0), gp("LEGS", "Bronze platelegs", 1, 0, 0),

            gp("SHIELD", "Rune kiteshield", 40, 0, 0), gp("SHIELD", "Adamant kiteshield", 30, 0, 0),
            gp("SHIELD", "Mithril kiteshield", 20, 0, 0), gp("SHIELD", "Black kiteshield", 10, 0, 0),
            gp("SHIELD", "Steel kiteshield", 5, 0, 0), gp("SHIELD", "Iron kiteshield", 1, 0, 0),
            gp("SHIELD", "Bronze kiteshield", 1, 0, 0), gp("SHIELD", "Wooden shield", 1, 0, 0),

            gp("HANDS", "Green d'hide vambraces", 40, 40, 0),
            gp("HANDS", "Leather vambraces", 1, 1, 0),
            gp("HANDS", "Rune gloves", 40, 0, 0), gp("HANDS", "Adamant gloves", 30, 0, 0),
            gp("HANDS", "Mithril gloves", 20, 0, 0), gp("HANDS", "Black gloves", 10, 0, 0),
            gp("HANDS", "Steel gloves", 5, 0, 0), gp("HANDS", "Iron gloves", 1, 0, 0),
            gp("HANDS", "Bronze gloves", 1, 0, 0), gp("HANDS", "Leather gloves", 1, 0, 0),

            gp("FEET", "Rune boots", 40, 0, 0), gp("FEET", "Adamant boots", 30, 0, 0),
            gp("FEET", "Mithril boots", 20, 0, 0), gp("FEET", "Black boots", 10, 0, 0),
            gp("FEET", "Steel boots", 5, 0, 0), gp("FEET", "Iron boots", 1, 0, 0),
            gp("FEET", "Bronze boots", 1, 0, 0), gp("FEET", "Fighting boots", 1, 0, 0),
            gp("FEET", "Leather boots", 1, 0, 0),

            gp("CAPE", "Black cape", 1, 0, 0), gp("CAPE", "Red cape", 1, 0, 0),
            gp("CAPE", "Blue cape", 1, 0, 0), gp("CAPE", "Yellow cape", 1, 0, 0),
            gp("CAPE", "Green cape", 1, 0, 0), gp("CAPE", "Orange cape", 1, 0, 0),
            gp("CAPE", "Purple cape", 1, 0, 0),
    };

    private static final GearPiece[] RANGED_ARMOUR_TIER = {
            gp("HEAD", "Green d'hide coif", 40, 40, 0),
            gp("HEAD", "Coif", 1, 20, 0), gp("HEAD", "Leather cowl", 1, 1, 0),

            gpQuest("BODY", "Green d'hide body", 40, 40, 0, DRAGON_SLAYER_BODY_QUEST),
            gp("BODY", "Studded body", 20, 20, 0), gp("BODY", "Hard leather body", 10, 1, 0),
            gp("BODY", "Leather body", 1, 1, 0),

            gp("LEGS", "Green d'hide chaps", 40, 40, 0),
            gp("LEGS", "Studded chaps", 20, 20, 0), gp("LEGS", "Leather chaps", 1, 1, 0),

            gp("HANDS", "Green d'hide vambraces", 40, 40, 0),
            gp("HANDS", "Leather vambraces", 1, 1, 0),

            gp("FEET", "Leather boots", 1, 1, 0), gp("FEET", "Fighting boots", 1, 0, 0),
            gp("FEET", "Fancy boots", 1, 0, 0),

            gp("CAPE", "Black cape", 1, 0, 0), gp("CAPE", "Red cape", 1, 0, 0),
            gp("CAPE", "Blue cape", 1, 0, 0), gp("CAPE", "Yellow cape", 1, 0, 0),
            gp("CAPE", "Green cape", 1, 0, 0), gp("CAPE", "Orange cape", 1, 0, 0),
            gp("CAPE", "Purple cape", 1, 0, 0),
    };

    private static final GearPiece[] MAGE_ARMOUR_TIER = {
            gp("HEAD", "Mystic hat", 20, 0, 20), gp("HEAD", "Wizard hat", 1, 0, 1),
            gp("HEAD", "Blue wizard hat", 1, 0, 1),

            gp("BODY", "Mystic robe top", 20, 0, 20), gp("BODY", "Wizard robe", 1, 0, 1),
            gp("BODY", "Blue wizard robe", 1, 0, 1),

            gp("LEGS", "Mystic robe bottom", 20, 0, 20), gp("LEGS", "Zamorak monk bottom", 1, 0, 1),
            gp("LEGS", "Monk's robe", 1, 0, 1),

            gp("HANDS", "Mystic gloves", 20, 0, 20),
            gp("HANDS", "Leather gloves", 1, 0, 0),

            gp("FEET", "Mystic boots", 20, 0, 20),
            gp("FEET", "Leather boots", 1, 0, 0),

            gp("CAPE", "Black cape", 1, 0, 0), gp("CAPE", "Red cape", 1, 0, 0),
            gp("CAPE", "Blue cape", 1, 0, 0), gp("CAPE", "Yellow cape", 1, 0, 0),
            gp("CAPE", "Green cape", 1, 0, 0), gp("CAPE", "Orange cape", 1, 0, 0),
            gp("CAPE", "Purple cape", 1, 0, 0),
    };

    private StyleArmourBankHelper() {
    }

    public static boolean canWear(CombatBotConfig.ImpsCombatStyle style, String itemName) {
        GearPiece g = findPiece(style, itemName);
        return g != null && canWearGear(g);
    }

    /**
     * Melee-plate/kite etc. (negatieve ranged bonus) — niet leather/d'hide die ook ranged is.
     */
    public static boolean isMeleeOnlyArmourName(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return false;
        }
        return findPiece(CombatBotConfig.ImpsCombatStyle.MELEE, itemName) != null
                && findPiece(CombatBotConfig.ImpsCombatStyle.RANGED, itemName) == null;
    }

    /** Iedere naam uit de melee-armor tier (incl. leather gloves/cape). */
    public static boolean isMeleeStyleArmourItemName(String itemName) {
        return itemName != null && !itemName.isEmpty()
                && findPiece(CombatBotConfig.ImpsCombatStyle.MELEE, itemName) != null;
    }

    /**
     * Imps melee: trek één melee-armorstuk uit (plate, helm, shield, …) — gewicht sparen.
     */
    public static boolean tryUnequipOneMeleeStyleArmourPiece() {
        try {
            var equipped = Equipment.getAll(item -> item != null && item.getName() != null
                    && isMeleeStyleArmourItemName(item.getName()));
            if (equipped == null || equipped.isEmpty()) {
                return false;
            }
            for (var eq : equipped) {
                if (eq == null) {
                    continue;
                }
                for (String action : new String[]{"Remove", "Unequip"}) {
                    if (eq.hasAction(action)) {
                        eq.interact(action);
                        DebugLog.log("Gear", "Imps melee: armor uit (" + eq.getName() + ", " + action + ")");
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Trek één stuk melee-armour uit (plate/kite) vóór ranged — boog + shield/kite past niet samen.
     *
     * @return true als Remove/Unequip is gestart (volgende tick verder)
     */
    public static boolean tryUnequipOneMeleeArmourBlockingRanged() {
        try {
            var equipped = Equipment.getAll(item -> item != null && item.getName() != null
                    && isMeleeOnlyArmourName(item.getName()));
            if (equipped == null || equipped.isEmpty()) {
                return false;
            }
            for (var eq : equipped) {
                if (eq == null) {
                    continue;
                }
                for (String action : new String[]{"Remove", "Unequip"}) {
                    if (eq.hasAction(action)) {
                        eq.interact(action);
                        DebugLog.log("Gear", "Ranged: melee armor uit (" + eq.getName() + ", " + action + ")");
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Event/cosmetic footwear zonder combat-stats — niet als gear-upgrade behandelen. */
    public static boolean isCosmeticOrNonCombatFootwear(String itemName) {
        if (itemName == null) {
            return false;
        }
        String lower = itemName.toLowerCase(Locale.ROOT);
        return lower.contains("beekeeper")
                || lower.contains("bunny")
                || lower.contains("graceful")
                || lower.contains("fremennik boots")
                || lower.contains("fancy boots");
    }

    /** Alle tier-slots hebben een passend stuk in equipment (geen bank-trip nodig voor armor). */
    public static boolean hasCoreArmourEquipped(CombatBotConfig.ImpsCombatStyle style) {
        GearPiece[] list = armourTierForStyle(style);
        for (String slot : gearSlots(list)) {
            if (bestEquippedForSlot(list, slot, style) == null) {
                return false;
            }
        }
        return true;
    }

    /** Alle armor-namen uit de tier-lijst (gear-prep keep-list). */
    public static List<String> armourItemNamesForStyle(CombatBotConfig.ImpsCombatStyle style) {
        GearPiece[] list = armourTierForStyle(style);
        List<String> names = new ArrayList<>();
        for (GearPiece g : list) {
            boolean dup = false;
            for (String n : names) {
                if (n.equalsIgnoreCase(g.name)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                names.add(g.name);
            }
        }
        return names;
    }

    /**
     * Eén armor-upgrade uit bank (beste slot eerst waar bank beter is dan owned).
     * {@code null} = niets te halen of bank niet open.
     */
    public static ArmourAction tryWithdrawOneUpgrade(CombatBotConfig.ImpsCombatStyle style) {
        if (!Bank.isOpen()) {
            return null;
        }
        GearPiece[] list = armourTierForStyle(style);
        for (String slot : gearSlots(list)) {
            GearPiece bankBest = bestBankGearForSlot(list, slot, style);
            if (bankBest == null) {
                continue;
            }
            int bankIdx = gearTierIndex(list, bankBest.name);

            // Al gedragen: zelfde of beter stuk — niet opnieuw withdrawen
            GearPiece equipped = bestEquippedForSlot(list, slot, style);
            if (equipped != null) {
                int eqIdx = gearTierIndex(list, equipped.name);
                if (eqIdx <= bankIdx) {
                    continue;
                }
            } else if (isItemEquipped(bankBest.name)) {
                continue;
            }

            GearPiece owned = bestOwnedGearForSlot(list, slot, style);
            int ownedIdx = owned != null ? gearTierIndex(list, owned.name) : Integer.MAX_VALUE;
            if (bankIdx >= ownedIdx) {
                continue;
            }
            // Al in inv (zelfde tier): equip-fase regelt dit, geen extra withdraw-lus
            if (owned != null && Inventory.contains(owned.name)
                    && owned.name.equalsIgnoreCase(bankBest.name)) {
                continue;
            }

            clearWorseGearBeforeUpgrade(list, slot, bankBest);
            if (!Bank.contains(bankBest.name)) {
                continue;
            }
            int have = BankInventoryHelper.countInInventory(bankBest.name);
            if (!BankInventoryHelper.hasSpaceForWithdraw(bankBest.name, 1, have)) {
                continue;
            }
            Bank.withdraw(bankBest.name, 1);
            return new ArmourAction(slot, bankBest.name, true);
        }
        return null;
    }

    /** Stort slechtere armor voor dit slot (inv + equipment) vóór upgrade-withdraw. */
    private static void clearWorseGearBeforeUpgrade(GearPiece[] list, String slot, GearPiece bankBest) {
        int bankIdx = gearTierIndex(list, bankBest.name);
        for (GearPiece g : list) {
            if (!g.slot.equals(slot)) {
                continue;
            }
            int gIdx = gearTierIndex(list, g.name);
            if (gIdx <= bankIdx) {
                continue;
            }
            if (Inventory.contains(g.name)) {
                Bank.depositAll(g.name);
            }
            if (isItemEquipped(g.name)) {
                try {
                    Bank.deposit(g.name, Integer.MAX_VALUE);
                } catch (Exception ignored) {
                }
            }
        }
        if ("FEET".equals(slot)) {
            depositCosmeticFootwearInInventory();
        }
    }

    private static void depositCosmeticFootwearInInventory() {
        for (IInventoryItem it : Inventory.getAll()) {
            if (it == null || it.getName() == null) {
                continue;
            }
            if (isCosmeticOrNonCombatFootwear(it.getName())) {
                Bank.depositAll(it.getName());
            }
        }
    }

    private static boolean isItemEquipped(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return false;
        }
        return Equipment.contains(item -> item != null && item.getName() != null
                && item.getName().equalsIgnoreCase(itemName));
    }

    private static GearPiece bestEquippedForSlot(GearPiece[] list, String slot,
                                                CombatBotConfig.ImpsCombatStyle style) {
        for (GearPiece g : list) {
            if (!g.slot.equals(slot) || !handsPieceAllowed(style, g) || !canWearGear(g)) {
                continue;
            }
            if (isItemEquipped(g.name)) {
                return g;
            }
        }
        return null;
    }

    /** Eén armor-stuk uit inventory aantrekken (beste per slot, level-check). */
    public static ArmourAction tryEquipOneFromInventory(CombatBotConfig config, CombatBotConfig.ImpsCombatStyle style) {
        if (style == CombatBotConfig.ImpsCombatStyle.RANGED && tryUnequipOneMeleeArmourBlockingRanged()) {
            return new ArmourAction("unequip", "melee-armor", false);
        }
        GearPiece[] list = armourTierForStyle(style);
        for (String slot : gearSlots(list)) {
            if (isSlotAlreadyEquippedForStyle(list, slot, style)) {
                continue;
            }
            for (GearPiece g : list) {
                if (!g.slot.equals(slot) || !handsPieceAllowed(style, g) || !canWearGear(g)) {
                    continue;
                }
                IInventoryItem hit = Inventory.getFirst(inv -> inv != null && inv.getName() != null
                        && g.name.equalsIgnoreCase(inv.getName()));
                if (hit == null) {
                    continue;
                }
                String action = hit.hasAction("Wear") ? "Wear" : (hit.hasAction("Wield") ? "Wield" : null);
                if (action == null) {
                    continue;
                }
                if (!tryEquipInventoryItem(config, hit, action)) {
                    continue;
                }
                return new ArmourAction(slot, g.name, false);
            }
        }
        return null;
    }

    /** Alle slots in één bank-sessie (Giants). */
    public static void withdrawAllForStyle(CombatBotConfig.ImpsCombatStyle style, SleepCallback sleep) {
        if (!Bank.isOpen()) {
            return;
        }
        GearPiece[] list = armourTierForStyle(style);
        for (String slot : gearSlots(list)) {
            GearPiece owned = bestOwnedGearForSlot(list, slot, style);
            GearPiece bankBest = bestBankGearForSlot(list, slot, style);
            if (bankBest == null) {
                continue;
            }
            int ownedIdx = owned != null ? gearTierIndex(list, owned.name) : Integer.MAX_VALUE;
            int bankIdx = gearTierIndex(list, bankBest.name);
            if (bankIdx >= ownedIdx) {
                continue;
            }
            if (owned != null && Inventory.contains(owned.name)) {
                Bank.depositAll(owned.name);
                if (sleep != null) {
                    sleep.sleep(250, 500);
                }
            }
            Bank.withdraw(bankBest.name, 1);
            if (sleep != null) {
                sleep.sleep(350, 650);
            }
        }
    }

    /** Alle style-armor uit inventory aantrekken (Giants). */
    public static void equipAllFromInventory(CombatBotConfig config, CombatBotConfig.ImpsCombatStyle style, SleepCallback sleep) {
        if (style == CombatBotConfig.ImpsCombatStyle.RANGED) {
            while (tryUnequipOneMeleeArmourBlockingRanged()) {
                if (sleep != null) {
                    sleep.sleep(400, 700);
                }
            }
        }
        GearPiece[] list = armourTierForStyle(style);
        for (String slot : gearSlots(list)) {
            if (isSlotAlreadyEquippedForStyle(list, slot, style)) {
                continue;
            }
            for (GearPiece g : list) {
                if (!g.slot.equals(slot) || !handsPieceAllowed(style, g) || !canWearGear(g)) {
                    continue;
                }
                IInventoryItem hit = Inventory.getFirst(inv -> inv != null && inv.getName() != null
                        && g.name.equalsIgnoreCase(inv.getName()));
                if (hit == null) {
                    continue;
                }
                String action = hit.hasAction("Wear") ? "Wear" : (hit.hasAction("Wield") ? "Wield" : null);
                if (action == null) {
                    continue;
                }
                if (!tryEquipInventoryItem(config, hit, action)) {
                    continue;
                }
                if (sleep != null) {
                    sleep.sleep(350, 650);
                }
                break;
            }
        }
    }

    private static boolean tryEquipInventoryItem(CombatBotConfig config, IInventoryItem hit, String action) {
        if (hit == null || action == null) {
            return false;
        }
        if ("Wield".equals(action)) {
            return InventoryEquipHelper.tryWieldOrWear(config, hit);
        }
        InventoryActionHelper.interact(config, hit, action);
        return true;
    }

    private static boolean isSlotAlreadyEquippedForStyle(GearPiece[] list, String slot,
                                                         CombatBotConfig.ImpsCombatStyle style) {
        return bestEquippedForSlot(list, slot, style) != null;
    }

    private static GearPiece findPiece(CombatBotConfig.ImpsCombatStyle style, String itemName) {
        if (itemName == null) {
            return null;
        }
        for (GearPiece g : armourTierForStyle(style)) {
            if (g.name.equalsIgnoreCase(itemName)) {
                return g;
            }
        }
        return null;
    }

    private static boolean canWearGear(GearPiece g) {
        if (g == null) {
            return false;
        }
        if (g.requiredQuest != null && !StormQuestHelper.isQuestFinished(g.requiredQuest)) {
            return false;
        }
        return safeDefenseLevel() >= g.defenseReq
                && safeRangedLevel() >= g.rangedReq
                && safeMagicLevel() >= g.magicReq;
    }

    /** Of dit item Dragon Slayer I nodig heeft (rune platebody / green d'hide body). */
    public static boolean requiresDragonSlayerToWear(String itemName) {
        if (itemName == null) {
            return false;
        }
        for (GearPiece g : MELEE_ARMOUR_TIER) {
            if (g.name.equalsIgnoreCase(itemName) && g.requiredQuest == DRAGON_SLAYER_BODY_QUEST) {
                return true;
            }
        }
        for (GearPiece g : RANGED_ARMOUR_TIER) {
            if (g.name.equalsIgnoreCase(itemName) && g.requiredQuest == DRAGON_SLAYER_BODY_QUEST) {
                return true;
            }
        }
        return false;
    }

    private static int safeDefenseLevel() {
        try {
            return Skills.getLevel(Skill.DEFENCE);
        } catch (Throwable ignored) {
            return 1;
        }
    }

    private static int safeRangedLevel() {
        try {
            return Skills.getLevel(Skill.RANGED);
        } catch (Throwable ignored) {
            return 1;
        }
    }

    private static int safeMagicLevel() {
        try {
            return Skills.getLevel(Skill.MAGIC);
        } catch (Throwable ignored) {
            return 1;
        }
    }

    private static GearPiece[] armourTierForStyle(CombatBotConfig.ImpsCombatStyle style) {
        switch (style) {
            case MELEE:
                return MELEE_ARMOUR_TIER;
            case RANGED:
                return RANGED_ARMOUR_TIER;
            case MAGE:
                return MAGE_ARMOUR_TIER;
            default:
                return new GearPiece[0];
        }
    }

    private static int gearTierIndex(GearPiece[] list, String name) {
        if (name == null) {
            return -1;
        }
        for (int i = 0; i < list.length; i++) {
            if (list[i].name.equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    private static GearPiece bestOwnedGearForSlot(GearPiece[] list, String slot,
                                                  CombatBotConfig.ImpsCombatStyle style) {
        for (GearPiece g : list) {
            if (!g.slot.equals(slot) || !handsPieceAllowed(style, g)) {
                continue;
            }
            if (isCosmeticOrNonCombatFootwear(g.name)) {
                continue;
            }
            boolean owned = Inventory.contains(item -> item != null && item.getName() != null
                    && g.name.equalsIgnoreCase(item.getName()))
                    || Equipment.contains(item -> item != null && item.getName() != null
                    && g.name.equalsIgnoreCase(item.getName()));
            if (owned && canWearGear(g)) {
                return g;
            }
        }
        return null;
    }

    private static GearPiece bestBankGearForSlot(GearPiece[] list, String slot,
                                                 CombatBotConfig.ImpsCombatStyle style) {
        for (GearPiece g : list) {
            if (!g.slot.equals(slot) || !handsPieceAllowed(style, g)) {
                continue;
            }
            if (!canWearGear(g)) {
                continue;
            }
            if (Bank.contains(g.name)) {
                return g;
            }
        }
        return null;
    }

    /** Ranged: alleen vambraces. Mage: alleen gloves. Melee: vambraces vóór gloves in tier-lijst. */
    private static boolean handsPieceAllowed(CombatBotConfig.ImpsCombatStyle style, GearPiece g) {
        if (g == null || !"HANDS".equals(g.slot)) {
            return true;
        }
        if (style == null) {
            return true;
        }
        boolean vamb = isVambraceName(g.name);
        boolean glove = isGloveName(g.name);
        switch (style) {
            case RANGED:
                return vamb;
            case MAGE:
                return glove && !vamb;
            case MELEE:
                return vamb || glove;
            default:
                return true;
        }
    }

    private static boolean isVambraceName(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).contains("vambrace");
    }

    private static boolean isGloveName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("glove") && !lower.contains("vambrace");
    }

    private static List<String> gearSlots(GearPiece[] list) {
        List<String> out = new ArrayList<>();
        for (GearPiece g : list) {
            if (!out.contains(g.slot)) {
                out.add(g.slot);
            }
        }
        return out;
    }
}
