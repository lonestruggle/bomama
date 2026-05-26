package com.combatbot;

import net.storm.api.domain.items.IInventoryItem;
import net.storm.sdk.items.Bank;
import net.storm.sdk.items.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Eén-klik storten: {@link Bank#depositAllExcept(keep)} (alles behalve keep + Genie-lamp).
 * Gebruik dit i.p.v. losse {@link Bank#deposit(String, int)}-loops — zelfde idee als
 * {@link Bank#depositInventory()} maar mét uitzonderingen voor tool/bait/coins.
 */
public final class BankDepositHelper {

    public static final int GENIE_LAMP_ITEM_ID = MiningDropHelper.GENIE_LAMP_ITEM_ID;

    private BankDepositHelper() {
    }

    public static boolean isNeverDeposited(IInventoryItem item) {
        if (item == null) {
            return true;
        }
        if (item.getId() == GENIE_LAMP_ITEM_ID) {
            return true;
        }
        String n = item.getName();
        return n != null && n.toLowerCase(Locale.ROOT).contains("lamp");
    }

    public static String[] mergeKeepWithGenieLamp(String... keep) {
        List<String> names = new ArrayList<>();
        if (keep != null) {
            for (String k : keep) {
                if (k == null || k.isEmpty()) {
                    continue;
                }
                boolean dup = false;
                for (String ex : names) {
                    if (ex.equalsIgnoreCase(k)) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    names.add(k);
                }
            }
        }
        appendGenieLampName(names);
        return names.toArray(new String[0]);
    }

    private static void appendGenieLampName(List<String> names) {
        IInventoryItem lamp = Inventory.getFirst(BankDepositHelper::isNeverDeposited);
        if (lamp == null || lamp.getName() == null || lamp.getName().isEmpty()) {
            return;
        }
        for (String n : names) {
            if (n.equalsIgnoreCase(lamp.getName())) {
                return;
            }
        }
        names.add(lamp.getName());
    }

    /**
     * Stort alles behalve {@code keep} + eventuele Genie-lamp in één actie (Bank all-except).
     */
    public static void depositAllExceptKeep(String... keep) {
        String[] merged = mergeKeepWithGenieLamp(keep);
        Bank.depositAllExcept(merged);
    }

    /**
     * Volledige inventaris naar bank (geen keep). Alleen als je echt niets op zak hoeft te houden.
     */
    public static void depositEntireInventory() {
        Bank.depositInventory();
    }

    /** Stort volledige inventaris behalve Genie-lamp (skill-switch cleanup). */
    public static void depositEntireInventoryExceptGenieLamp() {
        depositAllExceptKeep();
    }

    public static boolean hasDepositableJunk(Set<String> keepNamesLower) {
        return Inventory.contains(item -> {
            if (!(item instanceof IInventoryItem)) {
                return false;
            }
            IInventoryItem inv = (IInventoryItem) item;
            return inv.getName() != null
                    && !isNeverDeposited(inv)
                    && !keepNamesLower.contains(inv.getName().toLowerCase(Locale.ROOT));
        });
    }
}
