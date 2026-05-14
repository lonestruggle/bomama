package com.combatbot;

import net.runelite.api.Skill;
import net.storm.api.domain.items.IInventoryItem;
import net.storm.sdk.game.Skills;
import net.storm.sdk.items.Bank;
import net.storm.sdk.items.Equipment;
import net.storm.sdk.items.Inventory;

import java.util.Random;

/**
 * VarrockTeleportHelper — 4-fase F2P Varrock Teleport puzzel.
 *
 * Varrock Teleport: 1 Law, 3 Air, 1 Fire rune (Magic 25+).
 * 
 * Fase 1 (Hard No): Magic < 25 OF geen Law rune (bank/inv) → WALK.
 * Fase 2 (Equipment): Staff dekt fire/air → minimale runes pakken.
 * Fase 3 (Bank Combo Zoeker): Zoek rune/staff combinaties in strikte volgorde.
 * Fase 4 (Fallback): Niets gevonden → WALK.
 *
 * Aanroepen terwijl bank OPEN is. Pakt alle benodigdheden in inventory.
 */
public final class VarrockTeleportHelper {

    private VarrockTeleportHelper() {}

    private static final int MAGIC_LEVEL_REQUIRED = 25;
    private static final Random random = new Random();

    public enum TeleportReadiness {
        /** Alle benodigdheden in inventory, klaar om te teleporteren. */
        TELEPORT_READY,
        /**
         * Chronicle (in inv of equip) is bruikbaar — caller moet
         * {@link ChronicleHelper#teleportToVarrock(String)} aanroepen i.p.v. spell te casten.
         */
        CHRONICLE_TELEPORT_READY,
        /** Niet mogelijk — loop naar bestemming. */
        WALK
    }

    private static void debug(String msg) {
        DebugLog.log("VarrockTP", msg);
    }

    /**
     * Variant met chronicle-toggle (per-account). Probeert in volgorde:
     * <ol>
     *   <li>Chronicle in inv/equip met charges &gt; 0 → {@link TeleportReadiness#CHRONICLE_TELEPORT_READY}</li>
     *   <li>Chronicle in inv + Teleport cards in inv → laad 1 card → {@link TeleportReadiness#CHRONICLE_TELEPORT_READY}</li>
     *   <li>Bestaande spell-flow ({@link #prepareVarrockTeleport(boolean)}) — Magic 25, runes, staves</li>
     * </ol>
     * Bank hoeft alleen open te zijn voor de spell-flow; chronicle teleport werkt zonder bank.
     *
     * @param displayName ingelogde RSN — gebruikt voor JSON-state updates.
     * @param useChronicle account-toggle (per-account vlag, zie ManagedJagexAccountRow#useChronicleForVarrock).
     */
    public static TeleportReadiness prepareVarrockTeleportSmart(String displayName, boolean useChronicle) {
        if (useChronicle) {
            TeleportReadiness viaBook = tryChroniclePath(displayName);
            if (viaBook != null) return viaBook;
        }
        // Bestaande spell-flow vereist een open bank — hou dat gedrag intact.
        return prepareVarrockTeleport();
    }

    /**
     * Probeer een chronicle-teleport uit te voeren (per-account toggle uit JSON).
     * Returns {@code true} als de teleport gestart is — caller skipt dan de spell-cast.
     * Returns {@code false} als chronicle uit staat / niet beschikbaar — caller doet zijn
     * bestaande spell-flow (Magic 25 Varrock teleport).
     */
    public static boolean tryExecuteChronicleTeleport(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) return false;
        boolean useChronicle = false;
        try {
            AccountStateJsonStore.AccountEntry e = AccountStateJsonStore.getEntry(displayName);
            if (e != null) useChronicle = e.chronicleTeleportEnabled;
        } catch (Throwable ignored) {
            return false;
        }
        if (!useChronicle) return false;

        TeleportReadiness r = tryChroniclePath(displayName);
        if (r == TeleportReadiness.CHRONICLE_TELEPORT_READY) {
            return ChronicleHelper.teleportToVarrock(displayName);
        }
        return false;
    }

    /**
     * Probeer eerst chronicle te gebruiken; returns {@code null} als chronicle-pad niet beschikbaar is
     * (caller mag dan terugvallen op de spell-flow).
     */
    private static TeleportReadiness tryChroniclePath(String displayName) {
        // Refresh state-velden vanuit inv (voor zover dat zonder bank kan).
        ChronicleHelper.syncCardsInInventory(displayName);

        boolean inInv = ChronicleHelper.inInventory();
        boolean equip = ChronicleHelper.isEquipped();
        if (!inInv && !equip) {
            debug("chronicle pad: geen chronicle in inv/equip → val terug op spell/walk");
            return null;
        }
        if (!inInv && equip) {
            debug("chronicle pad: chronicle is equipped — worn-widget teleport pas in volgende update,"
                    + " val voor nu terug op spell/walk. Leg chronicle in inv voor direct gebruik.");
            return null;
        }

        AccountStateJsonStore.AccountEntry entry = AccountStateJsonStore.getEntry(displayName);
        int charges = entry == null ? 0 : Math.max(0, entry.chronicleCharges);

        if (charges > 0) {
            debug("chronicle pad: " + charges + " charges (state) → CHRONICLE_TELEPORT_READY");
            return TeleportReadiness.CHRONICLE_TELEPORT_READY;
        }

        // Geen charges, maar evt cards in inv én chronicle in inv → laad 1 card en ga.
        if (ChronicleHelper.cardsInInventory() > 0) {
            int loaded = ChronicleHelper.chargeAvailableCardsBatch(displayName, 1);
            if (loaded > 0) {
                debug("chronicle pad: 1 card geladen → CHRONICLE_TELEPORT_READY");
                return TeleportReadiness.CHRONICLE_TELEPORT_READY;
            }
        }

        debug("chronicle pad: geen charges/cards bruikbaar → val terug op spell/walk");
        return null;
    }

    /**
     * Voer de 4-fase check uit en pak benodigde items uit de bank.
     * Bank MOET open zijn bij aanroep.
     *
     * @return TELEPORT_READY als alle runes/staves in inventory zitten, WALK als het niet lukt.
     */
    public static TeleportReadiness prepareVarrockTeleport() {
        return prepareVarrockTeleport(true);
    }

    public static TeleportReadiness prepareVarrockTeleport(boolean allowStaffFallback) {
        if (!Bank.isOpen()) {
            debug("prepareVarrockTeleport: bank niet open → WALK");
            return TeleportReadiness.WALK;
        }

        // ---- FASE 1: Hard No ----
        int magicLevel;
        try {
            magicLevel = Skills.getLevel(Skill.MAGIC);
        } catch (Exception e) {
            debug("prepareVarrockTeleport: kan magic level niet lezen → WALK");
            return TeleportReadiness.WALK;
        }

        if (magicLevel < MAGIC_LEVEL_REQUIRED) {
            debug("prepareVarrockTeleport: Magic " + magicLevel + " < 25 → WALK");
            return TeleportReadiness.WALK;
        }

        boolean hasLawInv = getInvCount("Law rune") > 0;
        boolean hasLawBank = Bank.contains("Law rune");
        if (!hasLawInv && !hasLawBank) {
            debug("prepareVarrockTeleport: geen Law rune (inv/bank) → WALK");
            return TeleportReadiness.WALK;
        }

        // Pak 1 Law rune als we die niet hebben
        if (!hasLawInv) {
            Bank.withdraw("Law rune", 1);
            conditionalSleep();
        }

        // ---- FASE 2: Equipment check ----
        boolean staffCoversFire = hasEquippedStaffWithElement("fire");
        boolean staffCoversAir = hasEquippedStaffWithElement("air");

        if (staffCoversFire && staffCoversAir) {
            // Beide gedekt door staves → geen extra runes nodig
            debug("prepareVarrockTeleport: staff dekt fire+air → TELEPORT_READY");
            return TeleportReadiness.TELEPORT_READY;
        }

        if (staffCoversFire) {
            // Fire gedekt, need 3 Air runes
            if (ensureRuneInInventory("Air rune", 3)) {
                debug("prepareVarrockTeleport: staff dekt fire, 3 air gepakt → TELEPORT_READY");
                return TeleportReadiness.TELEPORT_READY;
            }
        }

        if (staffCoversAir) {
            // Air gedekt, need 1 Fire rune
            if (ensureRuneInInventory("Fire rune", 1)) {
                debug("prepareVarrockTeleport: staff dekt air, 1 fire gepakt → TELEPORT_READY");
                return TeleportReadiness.TELEPORT_READY;
            }
        }

        // ---- FASE 3: Bank Combo Zoeker ----

        // Optie 1: 1 Fire + 3 Air runes (simpelste)
        if (ensureRuneInInventory("Fire rune", 1) && ensureRuneInInventory("Air rune", 3)) {
            debug("prepareVarrockTeleport: 1 fire + 3 air uit bank → TELEPORT_READY");
            return TeleportReadiness.TELEPORT_READY;
        }

        // Optie 2/3: precies EEN staff gebruiken als dat de ontbrekende rune-kant oplost.
        // Nooit beide staves tegelijk proberen.
        int missingFire = Math.max(0, 1 - getInvCount("Fire rune"));
        int missingAir = Math.max(0, 3 - getInvCount("Air rune"));

        // Alleen fire-kant mist
        if (allowStaffFallback && missingFire > 0 && missingAir == 0) {
            if (tryWithdrawAndEquipStaff("fire")) {
                debug("prepareVarrockTeleport: fire staff gepakt/equipped (air al ok) → TELEPORT_READY");
                return TeleportReadiness.TELEPORT_READY;
            }
        }
        // Alleen air-kant mist
        if (allowStaffFallback && missingAir > 0 && missingFire == 0) {
            if (tryWithdrawAndEquipStaff("air")) {
                debug("prepareVarrockTeleport: air staff gepakt/equipped (fire al ok) → TELEPORT_READY");
                return TeleportReadiness.TELEPORT_READY;
            }
        }
        // Beide kanten missen: kies 1 staff + runes voor de andere kant
        if (allowStaffFallback && missingFire > 0 && missingAir > 0) {
            if (tryWithdrawAndEquipStaff("fire") && ensureRuneInInventory("Air rune", 3)) {
                debug("prepareVarrockTeleport: fire staff + 3 air → TELEPORT_READY");
                return TeleportReadiness.TELEPORT_READY;
            }
            if (tryWithdrawAndEquipStaff("air") && ensureRuneInInventory("Fire rune", 1)) {
                debug("prepareVarrockTeleport: air staff + 1 fire → TELEPORT_READY");
                return TeleportReadiness.TELEPORT_READY;
            }
        }

        // ---- FASE 4: Fallback ----
        debug("prepareVarrockTeleport: geen combinatie gevonden → WALK");
        return TeleportReadiness.WALK;
    }

    // ===================== HELPERS =====================

    private static boolean hasEquippedStaffWithElement(String element) {
        return Equipment.contains(item -> {
            if (item == null || item.getName() == null) return false;
            String n = item.getName().toLowerCase();
            return n.contains(element) && n.contains("staff");
        });
    }

    /**
     * Zorg dat we minstens 'needed' van deze rune in inventory hebben.
     * Trekt bij uit bank als nodig. Returns false als we niet genoeg kunnen krijgen.
     */
    private static boolean ensureRuneInInventory(String runeName, int needed) {
        int have = getInvCount(runeName);
        if (have >= needed) return true;
        if (!Bank.contains(runeName)) return false;
        int toWithdraw = needed - have;
        Bank.withdraw(runeName, toWithdraw);
        conditionalSleep();
        return getInvCount(runeName) >= needed;
    }

    private static int getInvCount(String name) {
        if (name == null || name.isEmpty()) return 0;
        return Inventory.getCount(true, name);
    }

    private static boolean tryWithdrawAndEquipStaff(String element) {
        if (hasEquippedStaffWithElement(element)) return true;

        String staffName = "Staff of " + element;
        String battlestaff = element.substring(0, 1).toUpperCase() + element.substring(1) + " battlestaff";

        if (!Inventory.contains(staffName) && !Inventory.contains(battlestaff)) {
            if (Bank.contains(staffName)) {
                Bank.withdraw(staffName, 1);
                conditionalSleep();
            } else if (Bank.contains(battlestaff)) {
                Bank.withdraw(battlestaff, 1);
                conditionalSleep();
            } else {
                return false;
            }
        }

        IInventoryItem invStaff = Inventory.getFirst(item -> {
            if (item == null || item.getName() == null) return false;
            String n = item.getName().toLowerCase();
            return n.contains(element) && n.contains("staff");
        });
        if (invStaff == null) return false;
        invStaff.interact("Wield");
        conditionalSleep();
        return hasEquippedStaffWithElement(element);
    }

    private static void conditionalSleep() {
        try {
            Thread.sleep(150 + random.nextInt(200));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
