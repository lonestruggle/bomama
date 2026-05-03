package com.combatbot;

import net.storm.sdk.items.Bank;
import net.storm.sdk.items.Equipment;
import net.storm.sdk.items.Inventory;
import net.storm.api.domain.items.IInventoryItem;

import java.util.*;

/**
 * UniversalBankingManager (UBM) v3 — Rustige, menselijke bank flow.
 *
 * Flow per bank sessie:
 * 1) SCAN: Bekijk wat we hebben en wat we nodig hebben (pre-scan)
 * 2) DEPOSIT: Zet items die we NIET nodig hebben op de bank, 1 game tick tussen elke actie
 * 3) WITHDRAW: Haal items die we WEL nodig hebben uit de bank, 1 game tick per actie
 * 4) VERIFY: Controleer of we alles hebben VOORDAT we OK teruggeven
 *
 * Geen haast: elke bank-interactie wacht 1 game tick (~600ms) voor menselijk tempo.
 */
public class UniversalBankingManager {
    private static final int GENIE_LAMP_ITEM_ID = 2528;

    private static void debug(String msg) {
        DebugLog.log("UBM", msg);
    }

    /** 1 game tick = ~600ms. Wacht dit tussen elke bank actie. */
    private static final int GAME_TICK_MS = 600;
    private static final int GAME_TICK_VARIANCE_MS = 150;

    private static final int DEPOSIT_TIMEOUT_MS = 3000;
    private static final int WITHDRAW_TIMEOUT_MS = 2500;

    private final Random random = new Random();

    // ===================== REQUIREMENT =====================

    /** Eén vereist item: naam, minimum in inv voordat we banken, target na withdraw. */
    public static final class Requirement {
        private final String itemName;
        private final int minAmount;
        private final int withdrawAmount;

        public Requirement(String itemName, int minAmount, int withdrawAmount) {
            this.itemName = itemName == null ? "" : itemName.trim();
            this.minAmount = Math.max(0, minAmount);
            this.withdrawAmount = Math.max(0, withdrawAmount);
        }

        public String getItemName() { return itemName; }
        public int getMinAmount() { return minAmount; }
        public int getWithdrawAmount() { return withdrawAmount; }
    }

    // ===================== RESULT =====================

    public enum BankSessionResult { OK, NEEDS_GE_RESTOCK, FAIL }

    public static final class BankSessionStatus {
        private final BankSessionResult result;
        private final String restockItemName;
        private final int restockShortage;

        private BankSessionStatus(BankSessionResult r, String name, int shortage) {
            this.result = r;
            this.restockItemName = name == null ? "" : name;
            this.restockShortage = shortage;
        }

        public static BankSessionStatus ok() {
            return new BankSessionStatus(BankSessionResult.OK, null, 0);
        }
        public static BankSessionStatus needsGeRestock(String name, int shortage) {
            return new BankSessionStatus(BankSessionResult.NEEDS_GE_RESTOCK, name, shortage);
        }
        public static BankSessionStatus fail() {
            return new BankSessionStatus(BankSessionResult.FAIL, null, 0);
        }

        public BankSessionResult getResult() { return result; }
        public String getRestockItemName() { return restockItemName; }
        public int getRestockShortage() { return restockShortage; }
        public boolean isOk() { return result == BankSessionResult.OK; }
        public boolean needsGeRestock() { return result == BankSessionResult.NEEDS_GE_RESTOCK; }
    }

    // ===================== PUBLIC API =====================

    /**
     * Check of de bot moet banken: minstens één requirement heeft
     * een inventory count onder zijn minAmount.
     */
    public boolean shouldBank(List<Requirement> requirements) {
        if (requirements == null || requirements.isEmpty()) return false;
        for (Requirement r : requirements) {
            if (r.getMinAmount() <= 0) continue;
            int have = getInvCount(r.getItemName());
            if (have < r.getMinAmount()) return true;
        }
        return false;
    }

    /**
     * Runt één volledige bank sessie met menselijk tempo.
     *
     * STAP 1: SCAN — Log wat we hebben en wat we nodig hebben.
     * STAP 2: DEPOSIT — Zet onnodige items weg, 1 game tick per actie.
     * STAP 3: WITHDRAW — Haal benodigde items, 1 game tick per actie.
     * STAP 4: VERIFY — Controleer of alles klopt voordat we OK returnen.
     *
     * Bank moet al open zijn. Sluit de bank NIET (handler doet dat).
     *
     * @return OK, NEEDS_GE_RESTOCK (met item naam + tekort), of FAIL
     */
    public BankSessionStatus runBankSession(List<Requirement> requirements) {
        if (!Bank.isOpen()) return BankSessionStatus.fail();
        if (requirements == null) requirements = Collections.emptyList();

        // Net na isOpen() kan Bank.contains nog even false zijn tot de bank-widget gesynct is.
        waitGameTick();
        debug("scan: settle tick (bank net open)");

        // ---- STAP 1: SCAN — Wat hebben we, wat hebben we nodig? ----
        Set<String> keepNamesLower = new HashSet<>();
        String[] keepNames = buildKeepArray(requirements);
        for (String k : keepNames) {
            if (k != null) keepNamesLower.add(k.toLowerCase());
        }

        debug("=== BANK SESSIE START ===");
        debug("scan: requirements:");
        for (Requirement r : requirements) {
            int have = getInvCount(r.getItemName());
            int need = r.getWithdrawAmount();
            String status = have >= need ? "✓ OK" : "✗ NODIG (+" + (need - have) + ")";
            debug("  " + r.getItemName() + ": hebben=" + have + " nodig=" + need + " → " + status);
        }

        // Scan junk items (alles dat niet in keep-list zit)
        boolean hasJunk = Inventory.contains(item ->
                item.getName() != null && !keepNamesLower.contains(item.getName().toLowerCase()));
        debug("scan: junk items in inventory=" + hasJunk);

        // ---- STAP 2: DEPOSIT — Weg met wat we niet nodig hebben ----
        boolean depositRan = false;
        if (hasJunk) {
            depositRan = true;
            debug("deposit: start — keep=" + Arrays.toString(keepNames));
            if (keepNames.length > 0) {
                Bank.depositAllExcept(keepNames);
            } else {
                // Geen keep-items: dump alles item-voor-item (menselijk)
                java.util.List<IInventoryItem> allItems = Inventory.getAll();
                if (allItems != null && !allItems.isEmpty()) {
                    java.util.Collections.shuffle(allItems, random);
                    for (IInventoryItem item : allItems) {
                        if (item != null && item.getName() != null) {
                            Bank.depositAll(item.getName());
                            waitGameTick(); // 1 game tick wachten na elke deposit
                        }
                    }
                }
            }

            // Wacht 1 game tick na deposit commando
            waitGameTick();

            // Conditional wait: wacht tot alleen keep-items overblijven
            waitUntilDepositComplete(keepNamesLower);
            // Forceer strikte cleanup: als er toch nog junk is, deponeer 1-voor-1.
            if (hasResidualJunk(keepNamesLower)) {
                debug("deposit: residual junk gevonden -> 1-voor-1 cleanup");
                forceDepositResidualJunk(keepNamesLower);
                waitUntilDepositComplete(keepNamesLower);
            }
            debug("deposit: afgerond");
        } else {
            debug("deposit: overgeslagen — geen junk items");
        }

        if (depositRan) {
            waitGameTick();
            waitGameTick();
            debug("deposit: extra settle voor bank-scan na stort");
        }

        // Stackables (runes/food) moeten unnoted withdraw zijn; anders stijgt getInvCount() niet → valse NEEDS_GE_RESTOCK.
        try {
            if (Bank.isNotedWithdrawMode()) {
                Bank.setWithdrawMode(false);
                waitGameTick();
            }
        } catch (Exception ignored) {
        }

        // ---- STAP 3: WITHDRAW — Haal wat we nodig hebben ----
        for (Requirement r : requirements) {
            String name = r.getItemName();
            if (name.isEmpty()) continue;
            int target = r.getWithdrawAmount();
            if (target <= 0) continue;

            int have = getInvCount(name);
            if (have >= target) {
                debug("withdraw: " + name + " al voldoende (" + have + "/" + target + ")");
                continue;
            }

            int need = target - have;

            // Check of item in bank zit (poll: API kan 1–2 ticks achterlopen op stort/open)
            if (!waitForBankContains(name, "withdraw")) {
                debug("withdraw: " + name + " NIET in bank → NEEDS_GE_RESTOCK (need=" + need + ")");
                return BankSessionStatus.needsGeRestock(name, need);
            }

            // Withdraw
            debug("withdraw: " + name + " → " + need + " stuks ophalen");
            int startCount = getInvCount(name);
            // quickWithdraw is betrouwbaarder voor "X" hoeveelheden (voert ook de X in)
            try {
                Bank.quickWithdraw(name, need);
            } catch (Exception e) {
                // fallback naar normale withdraw
                Bank.withdraw(name, need);
            }

            // Wacht 1 game tick na withdraw
            waitGameTick();

            // Conditional wait tot item daadwerkelijk in inventory zit
            boolean success = waitForWithdraw(name, startCount);

            // Verify na elke withdraw
            int nowHave = getInvCount(name);
            debug("withdraw: " + name + " verify → hebben=" + nowHave + " nodig=" + target);
            if (nowHave < target) {
                // Basis request: poging om precies `target` te pakken.
                // Als dit niet lukt (bijv. bank heeft minder stacks), dan pakken we alles wat we kunnen.
                debug("withdraw: " + name + " onvolledig (heb=" + nowHave + "/" + target + ") → probeer withdrawAll (alles wat werkt)");
                try {
                    if (bankContainsResilient(name)) {
                        Bank.withdrawAll(name);
                        waitGameTick();
                    }
                } catch (Exception ignored) {
                    // Soms faalt withdrawAll door UI/klik timing; we verifiëren straks op inventory.
                }

                int nowHave2 = getInvCount(name);
                debug("withdraw: " + name + " fallback verify → hebben=" + nowHave2 + "/" + target);
                if (nowHave2 <= 0) {
                    debug("withdraw: " + name + " fallback ook 0 → NEEDS_GE_RESTOCK");
                    return BankSessionStatus.needsGeRestock(name, target);
                }
                if (nowHave2 < target) {
                    if (nowHave2 >= r.getMinAmount()) {
                        debug("withdraw: " + name + " partial OK (" + nowHave2 + "/" + target
                                + ", min=" + r.getMinAmount() + ") → doorgaan zonder GE-loop");
                        continue;
                    }
                    // We hebben minder dan het minimum; nu rest alleen nog tekort via GE.
                    int remaining = Math.max(r.getMinAmount(), target) - nowHave2;
                    debug("withdraw: " + name + " nog onder minimum (" + nowHave2 + "/" + r.getMinAmount()
                            + ", target=" + target + ") → NEEDS_GE_RESTOCK (tekort=" + remaining + ")");
                    return BankSessionStatus.needsGeRestock(name, remaining);
                }

                debug("withdraw: " + name + " ✓ OK (fallback maakte het compleet: " + nowHave2 + "/" + target + ")");
                continue;
            }

            debug("withdraw: " + name + " ✓ OK");
        }

        // ---- STAP 4: VERIFY — Laatste controle voordat we OK zeggen ----
        debug("=== VERIFICATIE ===");
        boolean allGood = true;
        for (Requirement r : requirements) {
            String name = r.getItemName();
            if (name.isEmpty()) continue;
            int target = r.getWithdrawAmount();
            if (target <= 0) continue;

            int finalCount = getInvCount(name);
            int min = r.getMinAmount();
            boolean ok = finalCount >= min;
            String suffix = finalCount >= target ? "✓" : (ok ? "✓ partial" : "✗");
            debug("verify: " + name + " → " + finalCount + "/" + target + " (min=" + min + ") " + suffix);
            if (!ok) {
                debug("verify: FAIL — " + name + " onder minimum (tekort=" + (min - finalCount) + ")");
                return BankSessionStatus.needsGeRestock(name, min - finalCount);
            }
        }

        // Check dat er geen junk meer in inventory zit
        boolean stillHasJunk = hasResidualJunk(keepNamesLower);
        if (stillHasJunk) {
            debug("verify: residual junk na withdraw -> extra cleanup");
            forceDepositResidualJunk(keepNamesLower);
            stillHasJunk = hasResidualJunk(keepNamesLower);
            if (stillHasJunk) {
                debug("verify: WAARSCHUWING — nog junk items in inventory (niet-kritiek)");
            }
        }

        debug("=== BANK SESSIE COMPLEET — ALLES OK ===");
        return BankSessionStatus.ok();
    }

    /**
     * Wacht tot de bank UI gesloten is (conditional sleep).
     * Aanroepen na Bank.close() om race conditions te voorkomen.
     */
    public void waitForBankClose() {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (!Bank.isOpen()) {
                debug("waitForBankClose: bank gesloten");
                return;
            }
            sleepShort();
        }
        debug("waitForBankClose: timeout (bank mogelijk nog open)");
    }

    // ===================== INTERNAL HELPERS =====================

    private String[] buildKeepArray(List<Requirement> reqs) {
        List<String> names = new ArrayList<>();
        for (Requirement r : reqs) {
            if (!r.getItemName().isEmpty()) {
                names.add(r.getItemName());
            }
        }
        // Lamp (ID 2528) nooit proberen te banken/depositeren; dit kan loop/spam veroorzaken.
        // We voegen de actuele itemnaam toe zodat depositAllExcept dit item ongemoeid laat.
        IInventoryItem lamp = Inventory.getFirst(item -> item != null && item.getId() == GENIE_LAMP_ITEM_ID);
        if (lamp != null && lamp.getName() != null && !lamp.getName().isEmpty()) {
            boolean exists = false;
            for (String n : names) {
                if (n.equalsIgnoreCase(lamp.getName())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) names.add(lamp.getName());
        }
        return names.toArray(new String[0]);
    }

    /**
     * Wacht 1 game tick (~600ms ± variance) — menselijk tempo tussen bank acties.
     */
    private void waitGameTick() {
        try {
            int ms = GAME_TICK_MS + random.nextInt(GAME_TICK_VARIANCE_MS * 2) - GAME_TICK_VARIANCE_MS;
            ms = Math.max(450, ms); // minimaal 450ms
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Korte sleep voor polling loops (50-100ms).
     */
    private void sleepShort() {
        try {
            int ms = 50 + random.nextInt(50);
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final int BANK_CONTAINS_MAX_POLLS = 12;

    /** Exacte naam of case-insensitive match; soms faalt alleen de string-variant van de API. */
    private boolean bankContainsResilient(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return false;
        }
        try {
            if (Bank.contains(itemName)) {
                return true;
            }
            return Bank.contains(item ->
                    item != null && item.getName() != null
                            && item.getName().equalsIgnoreCase(itemName));
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Korte polling: direct na deposit/open geeft Bank.contains soms ten onrechte false. */
    private boolean waitForBankContains(String itemName, String ctx) {
        for (int i = 0; i < BANK_CONTAINS_MAX_POLLS; i++) {
            if (bankContainsResilient(itemName)) {
                if (i > 0) {
                    debug(ctx + ": bank item '" + itemName + "' zichtbaar na " + i + " poll(s)");
                }
                return true;
            }
            sleepShort();
        }
        return false;
    }

    /**
     * Conditional wait: controleer of alle non-keep items uit inventory zijn.
     */
    private void waitUntilDepositComplete(Set<String> keepNamesLower) {
        long deadline = System.currentTimeMillis() + DEPOSIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            boolean hasJunk = hasResidualJunk(keepNamesLower);
            if (!hasJunk) {
                debug("deposit verified: inventory clean");
                return;
            }
            sleepShort();
        }
        debug("deposit: timeout na " + DEPOSIT_TIMEOUT_MS + "ms");
    }

    private boolean hasResidualJunk(Set<String> keepNamesLower) {
        return Inventory.contains(item ->
                item != null && item.getName() != null
                        && !keepNamesLower.contains(item.getName().toLowerCase()));
    }

    /** Fallback cleanup: deponeer alle non-keep items individueel met game-tick pacing. */
    private void forceDepositResidualJunk(Set<String> keepNamesLower) {
        java.util.List<IInventoryItem> junkItems = Inventory.getAll(item ->
                item != null && item.getName() != null
                        && !keepNamesLower.contains(item.getName().toLowerCase()));
        if (junkItems == null || junkItems.isEmpty()) return;
        java.util.Collections.shuffle(junkItems, random);
        for (IInventoryItem item : junkItems) {
            if (item == null || item.getName() == null) continue;
            Bank.depositAll(item.getName());
            waitGameTick();
        }
    }

    /**
     * Conditional wait: controleer of inventory count voor item is gestegen.
     */
    private boolean waitForWithdraw(String itemName, int startCount) {
        long deadline = System.currentTimeMillis() + WITHDRAW_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            int current = getInvCount(itemName);
            if (current > startCount) {
                debug("withdraw verified: " + itemName + " " + startCount + " → " + current);
                return true;
            }
            sleepShort();
        }
        debug("withdraw timeout: " + itemName + " (startCount=" + startCount + ")");
        return false;
    }

    /**
     * Hoeveelheid in inventory + equipment (pijlen/amuletten/etc. die op het personage zitten).
     */
    private int getInvCount(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return 0;
        }
        int inv = Inventory.getCount(true, itemName);
        int eq = equippedCountMatching(itemName);
        return inv + eq;
    }

    private static int equippedCountMatching(String itemName) {
        try {
            var equipped = Equipment.getAll(item ->
                    item != null && item.getName() != null
                            && item.getName().equalsIgnoreCase(itemName));
            if (equipped == null || equipped.isEmpty()) {
                return 0;
            }
            int sum = 0;
            for (var e : equipped) {
                if (e != null) {
                    sum += Math.max(1, e.getQuantity());
                }
            }
            return sum;
        } catch (Exception ignored) {
            return 0;
        }
    }
}
