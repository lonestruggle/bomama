package com.combatbot;



import net.runelite.api.Skill;

import net.runelite.client.util.Text;

import net.storm.api.domain.actors.IPlayer;

import net.storm.sdk.entities.Players;

import net.storm.sdk.game.Game;

import net.storm.sdk.game.Skills;

import net.storm.sdk.items.Bank;



/**

 * Per-account ranged ammo (Combat, Imps, Giants, …):

 * vast pijl-type (standaard Bronze) of beste in bank.

 */

public final class RangedAmmoPreference {



    private RangedAmmoPreference() {

    }



    public static String activeDisplayName() {

        String rsn = BankSnapshotPlanner.currentDisplayName();

        if (rsn != null && !rsn.trim().isEmpty()) {

            return rsn.trim();

        }

        try {

            if (Game.isLoggedIn()) {

                IPlayer lp = Players.getLocal();

                if (lp != null && lp.getName() != null) {

                    return Text.removeTags(lp.getName()).trim();

                }

            }

        } catch (Throwable ignored) {

        }

        return null;

    }



    public static boolean useBestInBank(CombatBotConfig cfg) {

        return ManagedJagexAccountsStore.useBestRangedAmmoForDisplayName(cfg, activeDisplayName());

    }



    public static String preferredItemName(CombatBotConfig cfg) {

        return ManagedJagexAccountsStore.resolveRangedAmmoTypeForDisplayName(cfg, activeDisplayName());

    }



    /** Volgorde voor bank-withdraw (één type of beste-eerst). */

    public static String[] withdrawOrder(CombatBotConfig cfg) {

        return ManagedJagexAccountsStore.rangedAmmoWithdrawOrder(cfg, activeDisplayName());

    }



    /** Naam voor GE-koop bij tekort (niet BEST — dan Bronze). */

    public static String geBuyItemName(CombatBotConfig cfg) {

        if (useBestInBank(cfg)) {

            return ManagedJagexAccountsStore.RANGED_AMMO_DEFAULT;

        }

        String pref = preferredItemName(cfg);

        if (ManagedJagexAccountsStore.RANGED_AMMO_BEST.equals(pref)) {

            return ManagedJagexAccountsStore.RANGED_AMMO_DEFAULT;

        }

        return pref;

    }



    public static int requiredLevelForItem(String itemName) {

        if (itemName == null) {

            return 99;

        }

        for (Object[] entry : RangedAmmoKit.LEVELED_AMMO) {

            if (itemName.equalsIgnoreCase((String) entry[0])) {

                return (int) entry[1];

            }

        }

        return 1;

    }



    /**

     * Zoek ammo in bank: bij vaste voorkeur alleen dat type; bij BEST zoals leveled loop.

     */

    public static String findAmmoInOpenBank(CombatBotConfig cfg) {

        if (!Bank.isOpen()) {

            return null;

        }

        int rangedLevel;

        try {

            rangedLevel = Skills.getLevel(Skill.RANGED);

        } catch (Exception e) {

            rangedLevel = 1;

        }

        if (useBestInBank(cfg)) {

            for (Object[] entry : RangedAmmoKit.LEVELED_AMMO) {

                String name = (String) entry[0];

                int reqLevel = (int) entry[1];

                if (rangedLevel >= reqLevel && Bank.contains(name)) {

                    return name;

                }

            }

            return null;

        }

        String pref = preferredItemName(cfg);

        if (ManagedJagexAccountsStore.RANGED_AMMO_BEST.equals(pref)) {

            return null;

        }

        if (rangedLevel >= requiredLevelForItem(pref) && Bank.contains(pref)) {

            return pref;

        }

        return null;

    }



    public static boolean bankHasPreferredAmmo(CombatBotConfig cfg) {

        if (!Bank.isOpen()) {

            return false;

        }

        for (String name : withdrawOrder(cfg)) {

            if (Bank.contains(name)) {

                return true;

            }

        }

        return false;

    }

}


