package com.combatbot;

import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.Text;
import net.storm.api.domain.actors.INPC;
import net.storm.api.domain.actors.IPlayer;
import net.storm.api.domain.items.IInventoryItem;
import net.storm.api.domain.tiles.ITileItem;
import net.storm.api.plugins.config.ConfigManager;
import net.storm.sdk.entities.Players;
import net.storm.sdk.entities.TileItems;
import net.storm.sdk.game.Prices;
import net.storm.sdk.game.Skills;
import net.storm.sdk.items.Bank;
import net.storm.sdk.items.Equipment;
import net.storm.sdk.items.GrandExchange;
import net.storm.sdk.items.Inventory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F2P Barb fishing loot: trout/salmon van de grond bij Barbarian fishing spot.
 * Bank bij Edgeville (ook bijl, tinderbox, logs — geen woodcutting in deze modus).
 * Geen vis op de grond: als ook geen andere speler vist nabij spot → F2P hop; anders wacht 5–8 min, daarna hop.
 *
 * Optioneel: na X bank trips → Varrock GE: vis verkopen, daarna (reserve) Staff of air, Mind runes, Steel axe, Black axe (+50% guide),
 * Fly fishing rod (+20% guide). Te weinig gp voor een nog ontbrekend item uit die set → Barb loot uit, volgende skill.
 */
public class LootHandler {
    private static final int GENIE_LAMP_ITEM_ID = 2528;

    public static final WorldPoint EDGEVILLE_BANK_AREA = new WorldPoint(3096, 3492, 0);
    /** Gelijk aan default fishing center «Barbarian village» (trout/salmon op de grond bij de rivier). */
    public static final WorldPoint BARB_LOOT_CENTER = new WorldPoint(3104, 3433, 0);
    /** Varrock Grand Exchange (zelfde als fishing GE-restocks). */
    private static final WorldPoint VARROCK_GE = new WorldPoint(3165, 3487, 0);
    /** Grand Exchange bank — na visverkopen eerst alle coins hier vandaan voor juiste reserve in inventory. */
    private static final WorldPoint VARROCK_GE_BANK_AREA = new WorldPoint(3164, 3486, 0);
    private static final int GE_PROX = 12;
    private static final int GE_BANK_PROX = 22;
    private static final long BARB_GE_ACTION_MS = 500;
    /** Fallback item-IDs voor Prices als er geen item in inventory zit. */
    private static final int ID_TROUT = 333;
    private static final int ID_SALMON = 329;
    private static final int ID_RAW_TROUT = 335;
    private static final int ID_RAW_SALMON = 331;
    private static final int ID_MIND_RUNE = 561;
    private static final int ID_STAFF_OF_AIR = 1381;
    private static final int ID_STEEL_AXE = 1353;
    private static final int ID_BLACK_AXE = 1361;
    private static final int ID_FLY_FISHING_ROD = 309;

    /** Verkoopvolgorde: raw eerst, dan cooked (namen zoals in bank/inv). */
    private static final String[] BARB_GE_SELL_FISH = {
            "Raw trout", "Raw salmon", "Trout", "Salmon"
    };
    private static final int LOOT_RADIUS = 10;
    private static final int NEAR_ACTIVITY = 26;
    private static final int BANK_PROX = 18;
    private static final int SPOT_PROX = 14;
    private static final int PICKUP_REACH = 14;
    private static final long WAIT_MIN_MS = 5 * 60_000L;
    private static final long WAIT_MAX_MS = 8 * 60_000L;

    /** Veel gebruikte F2P werelden (geen garantie dat ze online blijven). */
    private static final int[] F2P_WORLDS = {
            301, 308, 316, 335, 379, 380, 381, 382, 383, 384, 385, 386, 387, 388, 389, 390, 393, 394,
            395, 396, 397, 398, 399, 413, 414, 415, 416, 417, 418, 419, 420, 427, 430, 431, 432, 433,
            434, 435, 436, 437, 438, 439, 445, 446, 449, 450, 451, 452, 453, 454, 455, 456, 457, 458,
            459, 460, 461, 462, 463, 464, 465, 466, 475, 477, 478, 480, 481, 482, 483, 484, 485, 486,
            487, 488, 489, 490, 491, 492, 493, 494, 495, 496, 499, 500, 501, 502, 503, 504, 505, 506,
            507, 508, 509, 510, 511, 512, 513, 514, 515, 516, 517, 518, 519, 520, 521, 522, 523, 524,
            525, 526, 527, 528, 529, 530, 531, 532, 533, 534, 535, 536, 537, 538, 539, 540, 541, 542,
            543, 544, 545, 546, 547, 548, 549, 550, 554, 556, 557, 558, 559, 562, 563, 564, 565, 570,
            571, 572, 573, 574, 575
    };

    private final CombatBotConfig config;
    private final AntiBan antiBan;
    private final CombatBotPaint paint;
    private final Random random = new Random();

    /** Optioneel — vis gebruikt FISHING markers voor excluded tiles. */
    private TileMarkerManager tileMarkerManager;

    /** RuneLite client thread — world hop widget werkt alleen daar (LoopedPlugin draait elders). */
    private ClientThread clientThread;
    /** Nodig om Barb loot automatisch te kunnen uitschakelen/skippen. */
    private ConfigManager configManager;

    private long lastTravelClick;
    private long waitUntilMs;
    private long lastPickupMs;
    /** Volgende toegestane vis-pickup (willekeurige tussen-pickup pauze). */
    private long nextFishPickupAllowedMs;

    /** Bank trips (alleen als er vis werd gestort) sinds laatste GE-run. */
    private int barbBankTripsSinceGe = 0;
    /** 0 = geen GE-trip; 1→bank; 2→bank UI; 3→GE lopen; 4→GE handelen. */
    private int barbGeStep = 0;
    private int barbGeSellIdx = 0;
    private String barbGeLockedSellItem = null;
    private int barbGeLockedSellPrice = -1;
    private boolean barbGeDidInitialCollect = false;
    private boolean barbGeStaffBuyTried = false;
    private boolean barbGeMindBuyTried = false;
    private boolean barbGeSteelAxeBuyTried = false;
    private boolean barbGeBlackAxeBuyTried = false;
    private boolean barbGeFlyRodBuyTried = false;
    /** Na vis verkocht + GE geclaimd: één keer bank coins → inv vóór staff/mind (min. cash reserve). */
    private boolean barbGeDidBankWithdrawAfterSell = false;
    private long lastBarbGeActionMs = 0;

    /** Tussen hops als er niemand vist rond Barb spot (anti-spam). */
    private static final long NO_FISHERS_NEARBY_HOP_COOLDOWN_MS = 9_000;
    /** Na world-hop: eerst even wachten tot drops zichtbaar/actief zijn. */
    private static final long WORLD_LOOT_VISIBILITY_GRACE_MS = 180_000L; // 3 min
    private long lastNoFishNearbyFisherHopMs = 0;
    /** Na GE-finish korte guard tegen directe 5/5 retrigger-loop. */
    private static final long BARB_GE_POST_FINISH_TRIP_COOLDOWN_MS = 120_000L;
    private long lastBarbGeCycleFinishMs = 0L;
    /** Hop-verificatie: als world niet wijzigt, opnieuw proberen. */
    private static final long HOP_VERIFY_TIMEOUT_MS = 9_000L;
    private int pendingHopTargetWorld = -1;
    private int pendingHopStartWorld = -1;
    private int pendingHopRetryCount = 0;
    private long pendingHopRequestedAtMs = 0L;
    private int lastObservedWorldId = -1;
    private long enteredCurrentWorldAtMs = 0L;

    private static final Pattern SKILL_TOTAL_ACTIVITY_DIGITS = Pattern.compile("(\\d{3,4})");

    public LootHandler(CombatBotConfig config, AntiBan antiBan, CombatBotPaint paint) {
        this.config = config;
        this.antiBan = antiBan;
        this.paint = paint;
    }

    public static WorldPoint getActivityCenter() {
        return BARB_LOOT_CENTER;
    }

    public void setClientThread(ClientThread clientThread) {
        this.clientThread = clientThread;
    }

    public void setConfigManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public void setTileMarkerManager(TileMarkerManager tileMarkerManager) {
        this.tileMarkerManager = tileMarkerManager;
    }

    private String tryLocalRsn() {
        try {
            IPlayer lp = Players.getLocal();
            if (lp == null || lp.getName() == null) {
                return null;
            }
            return Text.removeTags(lp.getName());
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean shouldStopBarbLootNow() {
        int coins = getCoinCount();
        if (coins >= 6000) {
            debug("Barb stop-check: coins>=6000 (" + coins + ")");
            return true;
        }
        int mindTarget = Math.max(0, config.barbLootGeMindRunes());
        int mindHave = Inventory.getCount(true, "Mind rune");
        if (hasAirStaff() && mindTarget > 0 && mindHave >= mindTarget) {
            debug("Barb stop-check: staff + mind target gehaald (" + mindHave + "/" + mindTarget + ")");
            return true;
        }
        return false;
    }

    private boolean maybeStopBarbLootAndSwitch() {
        if (!shouldStopBarbLootNow()) {
            return false;
        }
        String rsn = tryLocalRsn();
        if (configManager != null) {
            configManager.setConfiguration("combatbot", "barbLootEnabled", "false");
            configManager.setConfiguration("combatbot", "switchNow", "true");
            if (rsn != null && !rsn.isEmpty()) {
                BarbLootSkipStore.setSkipped(configManager, config, rsn);
            }
        }
        markBarbGeCycleFinished("goal reached");
        waitUntilMs = 0;
        paint.setLastAntiBanAction("✓ Barb loot klaar (coins/staff/runes) → volgende skill");
        debug("Barb loot auto-stop: rsn=" + rsn
                + ", coins=" + getCoinCount()
                + ", staff=" + hasAirStaff()
                + ", mind=" + Inventory.getCount(true, "Mind rune"));
        return true;
    }

    /** Barb loot uitzetten en rotatie doorzetten (GE: te weinig gp voor vereiste toolkit-onderdelen). */
    private void stopBarbLootForNextSkill(String reasonDetail) {
        String rsn = tryLocalRsn();
        if (configManager != null) {
            configManager.setConfiguration("combatbot", "barbLootEnabled", "false");
            configManager.setConfiguration("combatbot", "switchNow", "true");
            if (rsn != null && !rsn.isEmpty()) {
                BarbLootSkipStore.setSkipped(configManager, config, rsn);
            }
        }
        waitUntilMs = 0;
        paint.setLastAntiBanAction("Barb loot: " + reasonDetail + " -> volgende skill");
        debug("Barb loot stop -> next skill: " + reasonDetail);
    }

    private int finishBarbGeCannotAffordBarbToolkit(String itemHuman, int needGp, int budgetAfterReserve) {
        collectGeWithGuardedRetries(8);
        closeGeEsc();
        markBarbGeCycleFinished("insufficient gp: " + itemHuman);
        stopBarbLootForNextSkill("onvoldoende gp voor " + itemHuman
                + " (startprijs ~" + needGp + " gp, budget na reserve " + budgetAfterReserve + ")");
        return antiBan.varyDelay(rDelay(900, 1800));
    }

    private boolean isFishTileExcluded(WorldPoint wp) {
        if (tileMarkerManager == null || wp == null) return false;
        return tileMarkerManager.isTileExcluded(CombatBotPlugin.ActiveSkill.FISHING, wp);
    }

    public void resetState() {
        lastTravelClick = 0;
        waitUntilMs = 0;
        lastPickupMs = 0;
        nextFishPickupAllowedMs = 0;
        lastNoFishNearbyFisherHopMs = 0;
        barbBankTripsSinceGe = 0;
        lastBarbGeCycleFinishMs = 0L;
        pendingHopTargetWorld = -1;
        pendingHopStartWorld = -1;
        pendingHopRetryCount = 0;
        pendingHopRequestedAtMs = 0L;
        lastObservedWorldId = -1;
        enteredCurrentWorldAtMs = 0L;
        resetBarbGeState();
    }

    private void resetBarbGeState() {
        barbGeStep = 0;
        barbGeSellIdx = 0;
        barbGeLockedSellItem = null;
        barbGeLockedSellPrice = -1;
        barbGeDidInitialCollect = false;
        barbGeStaffBuyTried = false;
        barbGeMindBuyTried = false;
        barbGeSteelAxeBuyTried = false;
        barbGeBlackAxeBuyTried = false;
        barbGeFlyRodBuyTried = false;
        barbGeDidBankWithdrawAfterSell = false;
        lastBarbGeActionMs = 0;
    }

    /** Sentinel: {@link #maybeFinishBarbGeIfMindTargetNotMet} vindt geen reden om GE af te sluiten. */
    private static final int BARB_GE_CONTINUE = -1;

    /**
     * Eén guard: na mind-GE-poging ({@link #barbGeMindBuyTried}) — doel runes niet gehaald → GE dicht, weer looten.
     * Geen tweede GE-buy-poging; wel pas nadat de mind-sectie minstens één keer is gelopen (collect eerder in flow).
     */
    private int maybeFinishBarbGeIfMindTargetNotMet(int mindTargetCfg) {
        if (mindTargetCfg <= 0) return BARB_GE_CONTINUE;
        if (!barbGeMindBuyTried) return BARB_GE_CONTINUE;
        int have = Inventory.getCount(true, "Mind rune");
        if (have < mindTargetCfg) {
            return finishBarbGeReturnToLoot(
                    "🛒 Mind runes onvolledig (" + have + "/" + mindTargetCfg + ") → weer looten");
        }
        return BARB_GE_CONTINUE;
    }

    /** Sluit GE, reset Barb-GE state — terug naar normale vis-loot. */
    private int finishBarbGeReturnToLoot(String statusMsg) {
        collectGeWithGuardedRetries(8);
        closeGeEsc();
        markBarbGeCycleFinished("abort: " + statusMsg);
        paint.setLastAntiBanAction(statusMsg);
        debug("Barb GE afgebroken: " + statusMsg);
        return antiBan.varyDelay(rDelay(1200, 2200));
    }

    private void markBarbGeCycleFinished(String reason) {
        resetBarbGeState();
        // Hard reset: na GE-cyclus opnieuw op 0/threshold starten.
        barbBankTripsSinceGe = 0;
        lastBarbGeCycleFinishMs = System.currentTimeMillis();
        debug("Barb GE cycle finished -> reset trips to 0 (reason=" + reason + ")");
    }

    /**
     * GE collect met harde limiet om infinite UI-loops te voorkomen bij desync.
     */
    private void collectGeWithGuardedRetries(int maxAttempts) {
        int attempts = 0;
        try {
            while (attempts < Math.max(1, maxAttempts)
                    && GrandExchange.isOpen()
                    && GrandExchange.canCollect()
                    && !shouldAbortActions()) {
                attempts++;
                GrandExchange.collect(true);
                humanBankPauseMs(400, 701);
            }
            if (GrandExchange.isOpen() && GrandExchange.canCollect()) {
                debug("Barb GE collect guard geraakt na " + attempts + " poging(en) — sluit GE flow veilig af");
            }
        } catch (Throwable ignored) {
        }
    }

    private int getAccountTotalLevel() {
        int sum = 0;
        for (Skill s : Skill.values()) {
            try {
                sum += Skills.getLevel(s);
            } catch (Throwable ignored) {
            }
        }
        return sum;
    }

    /**
     * Minimale skill total uit activity (bijv. "750 Skill Total"). Alleen relevant als types SKILL_TOTAL bevat.
     */
    private static int parseMinSkillTotalFromWorld(net.runelite.api.World w, Collection<net.runelite.api.WorldType> types) {
        if (w == null || types == null || !types.contains(net.runelite.api.WorldType.SKILL_TOTAL)) {
            return 0;
        }
        String act = w.getActivity();
        if (act == null) return Integer.MAX_VALUE;
        Matcher m = SKILL_TOTAL_ACTIVITY_DIGITS.matcher(act);
        int minReq = Integer.MAX_VALUE;
        while (m.find()) {
            try {
                int n = Integer.parseInt(m.group(1));
                if (n >= 301 && n <= 2277) {
                    minReq = Math.min(minReq, n);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return minReq == Integer.MAX_VALUE ? 750 : minReq;
    }

    private static boolean isKnownF2pFallbackWorldId(int id) {
        for (int w : F2P_WORLDS) {
            if (w == id) return true;
        }
        return false;
    }

    /**
     * F2P-wereld waar deze account binnen mag (geen members, geen PVP-risico types, skill total OK).
     */
    private net.runelite.api.World pickAccessibleF2pWorld(net.runelite.api.Client rl) {
        int cur = rl.getWorld();
        int myTotal = getAccountTotalLevel();
        net.runelite.api.World[] list = rl.getWorldList();
        List<net.runelite.api.World> candidates = new ArrayList<>();
        if (list != null) {
            for (net.runelite.api.World w : list) {
                if (w == null) continue;
                int id = w.getId();
                if (id <= 0 || id == cur) continue;
                Collection<net.runelite.api.WorldType> types = w.getTypes();
                if (types == null || types.isEmpty()) {
                    if (isKnownF2pFallbackWorldId(id)) {
                        candidates.add(w);
                    }
                    continue;
                }
                if (types.contains(net.runelite.api.WorldType.MEMBERS)) continue;
                if (types.contains(net.runelite.api.WorldType.PVP)
                        || types.contains(net.runelite.api.WorldType.PVP_ARENA)
                        || types.contains(net.runelite.api.WorldType.BOUNTY)
                        || types.contains(net.runelite.api.WorldType.HIGH_RISK)) {
                    continue;
                }
                if (types.contains(net.runelite.api.WorldType.DEADMAN)
                        || types.contains(net.runelite.api.WorldType.TOURNAMENT_WORLD)
                        || types.contains(net.runelite.api.WorldType.BETA_WORLD)
                        || types.contains(net.runelite.api.WorldType.SEASONAL)
                        || types.contains(net.runelite.api.WorldType.LAST_MAN_STANDING)) {
                    continue;
                }
                int skillReq = parseMinSkillTotalFromWorld(w, types);
                if (skillReq > 0 && myTotal < skillReq) {
                    continue;
                }
                candidates.add(w);
            }
        }
        if (!candidates.isEmpty()) {
            return candidates.get(random.nextInt(candidates.size()));
        }
        if (list != null) {
            for (int fallbackId : F2P_WORLDS) {
                if (fallbackId == cur) continue;
                for (net.runelite.api.World w : list) {
                    if (w != null && w.getId() == fallbackId) {
                        Collection<net.runelite.api.WorldType> types = w.getTypes();
                        if (types != null && types.contains(net.runelite.api.WorldType.MEMBERS)) {
                            continue;
                        }
                        int skillReq = parseMinSkillTotalFromWorld(w, types);
                        if (skillReq > 0 && myTotal < skillReq) {
                            continue;
                        }
                        return w;
                    }
                }
            }
        }
        return null;
    }

    private int getCoinCount() {
        IInventoryItem c = Inventory.getFirst("Coins");
        return c != null ? c.getQuantity() : 0;
    }

    private boolean hasAirStaff() {
        return Inventory.contains("Staff of air") || Equipment.contains("Staff of air");
    }

    private boolean hasSteelAxe() {
        return Inventory.contains("Steel axe") || Equipment.contains("Steel axe");
    }

    private boolean hasBlackAxe() {
        return Inventory.contains("Black axe") || Equipment.contains("Black axe");
    }

    private boolean hasFlyFishingRod() {
        return Inventory.contains("Fly fishing rod");
    }

    private static int guidePriceOrId(String itemName, int fallbackItemId) {
        IInventoryItem it = Inventory.getFirst(itemName);
        int id = it != null ? it.getId() : fallbackItemId;
        int p = Prices.getItemPrice(id);
        return Math.max(1, p);
    }

    private int barbGeGuidePriceForFish(String fishName) {
        if (fishName == null) return 1;
        String n = fishName.toLowerCase(Locale.ROOT);
        if (n.contains("raw")) {
            if (n.contains("trout")) return guidePriceOrId("Raw trout", ID_RAW_TROUT);
            if (n.contains("salmon")) return guidePriceOrId("Raw salmon", ID_RAW_SALMON);
        } else {
            if (n.contains("trout")) return guidePriceOrId("Trout", ID_TROUT);
            if (n.contains("salmon")) return guidePriceOrId("Salmon", ID_SALMON);
        }
        return 1;
    }

    /** GE-voorbereiding: noted withdraw alle raw + cooked trout/salmon met menselijke pauze. */
    private void barbGeWithdrawAllFishFromBankNoted() {
        for (String fish : BARB_GE_SELL_FISH) {
            if (Bank.contains(fish)) {
                Bank.withdrawAll(fish);
                humanBankPauseMs(300, 501);
            }
        }
    }

    private boolean geHasActiveSellingOffer() {
        try {
            java.util.List<net.runelite.api.GrandExchangeOffer> offers = GrandExchange.getOffers();
            if (offers == null) return false;
            for (net.runelite.api.GrandExchangeOffer offer : offers) {
                if (offer != null && offer.getState() != null
                        && offer.getState().name().contains("SELLING")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void closeGeEsc() {
        try {
            net.storm.sdk.input.Keyboard.type(String.valueOf((char) java.awt.event.KeyEvent.VK_ESCAPE), false);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Na X keer banken met vis: Edge bank → trout/salmon noted withdraw → Varrock GE → verkoop (% onder guide) →
     * daarna staff of air, mind runes, steel/black axe (+50% guide), fly rod (+20%); bij te weinig gp voor ontbrekend onderdeel → volgende skill.
     */
    private int handleBarbGeFlow(IPlayer local) {
        if (shouldAbortActions()) {
            return antiBan.varyDelay(rDelay(250, 500));
        }
        WorldPoint me = local.getWorldLocation();
        if (me == null) return 800;

        switch (barbGeStep) {
            case 1:
                if (near(me, EDGEVILLE_BANK_AREA, BANK_PROX)) {
                    barbGeStep = 2;
                    return 400;
                }
                return walkTowards(EDGEVILLE_BANK_AREA, "🛒 Barb: bank (GE-voorbereiding)");

            case 2:
                if (!Bank.isOpen()) {
                    if (BankHelper.tryOpenFullBank()) {
                        return antiBan.varyDelay(rDelay(900, 1600));
                    }
                    BankHelper.interactIfNearby();
                    return 1200;
                }
                try {
                    if (!Bank.isNotedWithdrawMode()) {
                        Bank.setWithdrawMode(true);
                        humanBankPauseMs(350, 601);
                    }
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }
                barbGeWithdrawAllFishFromBankNoted();
                try {
                    if (Bank.isNotedWithdrawMode()) {
                        Bank.setWithdrawMode(false);
                        humanBankPauseMs(280, 501);
                    }
                } catch (Exception ignored) {
                }
                Bank.close();
                barbGeStep = 3;
                debug("Barb GE: vis uit bank, naar GE");
                paint.setLastAntiBanAction("🛒 Barb: naar GE (vis verkopen)");
                return antiBan.varyDelay(rDelay(700, 1200));

            case 3:
                if (near(me, VARROCK_GE, GE_PROX)) {
                    barbGeStep = 4;
                    barbGeSellIdx = 0;
                    barbGeLockedSellItem = null;
                    barbGeLockedSellPrice = -1;
                    barbGeDidInitialCollect = false;
                    barbGeStaffBuyTried = false;
                    barbGeMindBuyTried = false;
                    barbGeSteelAxeBuyTried = false;
                    barbGeBlackAxeBuyTried = false;
                    barbGeFlyRodBuyTried = false;
                    barbGeDidBankWithdrawAfterSell = false;
                    return 400;
                }
                return walkTowards(VARROCK_GE, "🛒 Barb: Grand Exchange");

            case 4:
                return handleBarbGeTrading(local);

            default:
                resetBarbGeState();
                return 600;
        }
    }

    /**
     * Na vis verkocht: GE sluiten, dichtstbijzijnde echte bank openen, alle coins naar inventory —
     * daarna klopt {@link #getCoinCount()} voor {@code barbLootGeMinCash} vóór staff/mind-inkoop.
     */
    private int barbGeSyncBankCoinsBeforeBuys(IPlayer local, long now) {
        if (shouldAbortActions()) {
            return antiBan.varyDelay(rDelay(250, 500));
        }
        if (GrandExchange.isOpen()) {
            closeGeEsc();
            lastBarbGeActionMs = now;
            paint.setCurrentStatus("🛒 Barb GE sluiten → bank (alle coins)...");
            debug("Barb GE sync: GE was open, eerst sluiten");
            return antiBan.varyDelay(rDelay(450, 900));
        }

        if (!Bank.isOpen()) {
            if (BankHelper.interactIfNearby()) {
                debug("Barb GE sync: bank dichtbij, open bank");
                return antiBan.varyDelay(rDelay(900, 1600));
            }
            if (BankHelper.walkToNearestFullBank()) {
                paint.setCurrentStatus("🏦 Barb: naar bank voor coins...");
                debug("Barb GE sync: loop naar dichtstbijzijnde bank");
                return antiBan.varyDelay(TravelWalkHelper.postClickDelayMs(config, random));
            }
            if (BankHelper.tryOpenFullBank()) {
                debug("Barb GE sync: fallback tryOpenFullBank");
                return antiBan.varyDelay(rDelay(900, 1600));
            }
            debug("Barb GE sync: geen bank interact mogelijk, retry");
            return antiBan.varyDelay(rDelay(900, 1500));
        }

        if (Bank.contains("Coins")) {
            debug("Barb GE: bank bevat coins, withdraw-all naar inv");
            Bank.withdrawAll("Coins");
            humanBankPauseMs(350, 601);
        } else {
            debug("Barb GE: bank bevat geen coins bij sync");
        }
        Bank.close();
        barbGeDidBankWithdrawAfterSell = true;
        int invCoins = getCoinCount();
        debug("Barb GE: coins uit bank; inventory gp=" + invCoins + ", reserve=" + Math.max(0, config.barbLootGeMinCash()));
        paint.setLastAntiBanAction("🛒 Coins uit bank (" + invCoins + " gp in inv) — GE inkopen");
        lastBarbGeActionMs = System.currentTimeMillis();
        return antiBan.varyDelay(rDelay(700, 1200));
    }

    private int handleBarbGeTrading(IPlayer local) {
        if (shouldAbortActions()) {
            return antiBan.varyDelay(rDelay(250, 500));
        }
        long now = System.currentTimeMillis();
        if (now - lastBarbGeActionMs < BARB_GE_ACTION_MS) {
            return antiBan.varyDelay(rDelay(200, 450));
        }

        boolean fishSellRoundDone = barbGeSellIdx >= BARB_GE_SELL_FISH.length;
        boolean geFullySettled = !geHasActiveSellingOffer() && !GrandExchange.canCollect();
        if (fishSellRoundDone && !barbGeDidBankWithdrawAfterSell && geFullySettled) {
            debug("Barb GE phase: verkoop klaar -> bank coins sync");
            return barbGeSyncBankCoinsBeforeBuys(local, now);
        }

        if (!GrandExchange.isOpen()) {
            WorldPoint meOpen = local.getWorldLocation();
            if (meOpen != null && !near(meOpen, VARROCK_GE, GE_PROX)) {
                return walkTowards(VARROCK_GE, "🛒 Barb GE: naar exchange…");
            }
            GrandExchange.open();
            lastBarbGeActionMs = now;
            paint.setCurrentStatus("🛒 Barb GE: openen…");
            return antiBan.varyDelay(rDelay(1400, 2400));
        }

        if (!barbGeDidInitialCollect && GrandExchange.canCollect()) {
            GrandExchange.collect(true);
            debug("Barb GE: initial collect -> bank");
            barbGeDidInitialCollect = true;
            lastBarbGeActionMs = now;
            return antiBan.varyDelay(rDelay(1000, 1800));
        }
        barbGeDidInitialCollect = true;

        int pctBelow = Math.max(0, Math.min(90, config.barbLootGePercentBelow()));

        if (barbGeSellIdx < BARB_GE_SELL_FISH.length) {
            String fishName = BARB_GE_SELL_FISH[barbGeSellIdx];
            IInventoryItem stack = Inventory.getFirst(fishName);
            if (stack == null) {
                barbGeSellIdx++;
                barbGeLockedSellItem = null;
                barbGeLockedSellPrice = -1;
                return antiBan.varyDelay(rDelay(250, 500));
            }
            if (barbGeLockedSellItem == null || !barbGeLockedSellItem.equalsIgnoreCase(fishName)
                    || barbGeLockedSellPrice <= 0) {
                int guide = barbGeGuidePriceForFish(fishName);
                barbGeLockedSellPrice = Math.max(1, (int) Math.round(guide * (100 - pctBelow) / 100.0));
                barbGeLockedSellItem = fishName;
                debug("Barb GE sell " + fishName + " @" + barbGeLockedSellPrice + " (" + pctBelow + "% onder guide " + guide + ")");
            }
            int qty = stack.getQuantity();
            paint.setCurrentStatus("🛒 Verkoop " + fishName + " (" + qty + ") @" + barbGeLockedSellPrice);
            boolean done = GrandExchange.exchange(false, fishName, qty, barbGeLockedSellPrice, true, true);
            lastBarbGeActionMs = System.currentTimeMillis();
            if (done) {
                barbGeSellIdx++;
                barbGeLockedSellItem = null;
                barbGeLockedSellPrice = -1;
                return antiBan.varyDelay(rDelay(1200, 2000));
            }
            return antiBan.varyDelay(rDelay(900, 1500));
        }

        if (geHasActiveSellingOffer()) {
            if (GrandExchange.canCollect()) {
                GrandExchange.collect(true);
                debug("Barb GE: tussentijds collect -> bank");
                lastBarbGeActionMs = System.currentTimeMillis();
                paint.setCurrentStatus("🛒 Barb GE: tussentijds collect…");
                return antiBan.varyDelay(rDelay(1000, 1800));
            }
            paint.setCurrentStatus("🛒 Barb GE: wacht op verkopen…");
            lastBarbGeActionMs = now;
            return antiBan.varyDelay(rDelay(2000, 4000));
        }

        if (GrandExchange.canCollect()) {
            GrandExchange.collect(true);
            debug("Barb GE: opbrengst collect -> bank");
            lastBarbGeActionMs = System.currentTimeMillis();
            paint.setCurrentStatus("🛒 Barb GE: opbrengst…");
            return antiBan.varyDelay(rDelay(1200, 2000));
        }

        int reserve = Math.max(0, config.barbLootGeMinCash());
        int mindTargetCfg = Math.max(0, config.barbLootGeMindRunes());
        int coinsNow = getCoinCount();
        debug("Barb GE funds-check: coinsInv=" + coinsNow
                + ", reserve=" + reserve
                + ", staffTried=" + barbGeStaffBuyTried
                + ", mindTried=" + barbGeMindBuyTried
                + ", steelTried=" + barbGeSteelAxeBuyTried
                + ", blackTried=" + barbGeBlackAxeBuyTried
                + ", rodTried=" + barbGeFlyRodBuyTried
                + ", syncedBankCoins=" + barbGeDidBankWithdrawAfterSell);

        if (config.barbLootGeBuyAirStaff() && barbGeStaffBuyTried && !hasAirStaff()) {
            return finishBarbGeReturnToLoot("🛒 Staff of air niet verkregen → weer looten");
        }

        if (!barbGeStaffBuyTried) {
            barbGeStaffBuyTried = true;
            if (config.barbLootGeBuyAirStaff() && !hasAirStaff()) {
                int coins = getCoinCount();
                int startStaff = Math.max(1, (int) Math.ceil(Prices.getItemPrice(ID_STAFF_OF_AIR) * 1.15));
                debug("Barb GE staff-check: coinsInv=" + coins + ", reserve=" + reserve + ", startStaff=" + startStaff);
                if (coins - reserve >= startStaff) {
                    paint.setCurrentStatus("🛒 Barb GE: Staff of air…");
                    GeRestockHelper.RestockResult rr = GeRestockHelper.buyWithEscalation(
                            "Staff of air", 1, startStaff, () -> config.botEnabled());
                    debug("Barb GE staff buy: " + rr);
                    lastBarbGeActionMs = System.currentTimeMillis();
                    if (rr != GeRestockHelper.RestockResult.SUCCESS) {
                        return finishBarbGeReturnToLoot("🛒 Staff of air GE mislukt → weer looten");
                    }
                    return antiBan.varyDelay(rDelay(2200, 4000));
                }
            }
        }

        if (!barbGeMindBuyTried) {
            barbGeMindBuyTried = true;
            int coins = getCoinCount();
            int targetTot = mindTargetCfg;
            int have = Inventory.getCount(true, "Mind rune");
            if (targetTot > have) {
                int startMind = Math.max(1, (int) Math.ceil(Math.max(1, Prices.getItemPrice(ID_MIND_RUNE)) * 1.12));
                int budget = coins - reserve;
                int maxExtra = Math.max(0, budget / Math.max(1, startMind));
                int desiredTotal = Math.min(targetTot, have + maxExtra);
                debug("Barb GE mind-check: coinsInv=" + coins
                        + ", reserve=" + reserve
                        + ", haveMind=" + have
                        + ", targetMind=" + targetTot
                        + ", budget=" + budget
                        + ", desiredTotal=" + desiredTotal
                        + ", startMind=" + startMind);
                if (desiredTotal > have) {
                    paint.setCurrentStatus("🛒 Barb GE: Mind rune → " + desiredTotal);
                    GeRestockHelper.RestockResult rr = GeRestockHelper.buyWithEscalation(
                            "Mind rune", desiredTotal, startMind, () -> config.botEnabled());
                    debug("Barb GE mind buy: " + rr + " target=" + desiredTotal);
                    lastBarbGeActionMs = System.currentTimeMillis();
                    if (rr != GeRestockHelper.RestockResult.SUCCESS) {
                        return finishBarbGeReturnToLoot("🛒 Mind runes GE mislukt → weer looten");
                    }
                    return antiBan.varyDelay(rDelay(2200, 4500));
                }
            }
        }

        if (!barbGeSteelAxeBuyTried) {
            barbGeSteelAxeBuyTried = true;
            if (!hasSteelAxe()) {
                int coins = getCoinCount();
                int startAxe = Math.max(1, (int) Math.ceil(Prices.getItemPrice(ID_STEEL_AXE) * 1.50));
                int budget = coins - reserve;
                debug("Barb GE steel axe: coins=" + coins + ", reserve=" + reserve + ", start=" + startAxe + ", budget=" + budget);
                if (budget < startAxe) {
                    return finishBarbGeCannotAffordBarbToolkit("Steel axe", startAxe, budget);
                }
                paint.setCurrentStatus("Barb GE: Steel axe");
                GeRestockHelper.RestockResult rr = GeRestockHelper.buyWithEscalation(
                        "Steel axe", 1, startAxe, () -> config.botEnabled());
                debug("Barb GE steel axe buy: " + rr);
                lastBarbGeActionMs = System.currentTimeMillis();
                if (rr != GeRestockHelper.RestockResult.SUCCESS) {
                    return finishBarbGeReturnToLoot("Steel axe GE mislukt -> weer looten");
                }
                return antiBan.varyDelay(rDelay(2200, 4000));
            }
        }

        if (!barbGeBlackAxeBuyTried) {
            barbGeBlackAxeBuyTried = true;
            if (!hasBlackAxe()) {
                int coins = getCoinCount();
                int startAxe = Math.max(1, (int) Math.ceil(Prices.getItemPrice(ID_BLACK_AXE) * 1.50));
                int budget = coins - reserve;
                debug("Barb GE black axe: coins=" + coins + ", reserve=" + reserve + ", start=" + startAxe + ", budget=" + budget);
                if (budget < startAxe) {
                    return finishBarbGeCannotAffordBarbToolkit("Black axe", startAxe, budget);
                }
                paint.setCurrentStatus("Barb GE: Black axe");
                GeRestockHelper.RestockResult rr = GeRestockHelper.buyWithEscalation(
                        "Black axe", 1, startAxe, () -> config.botEnabled());
                debug("Barb GE black axe buy: " + rr);
                lastBarbGeActionMs = System.currentTimeMillis();
                if (rr != GeRestockHelper.RestockResult.SUCCESS) {
                    return finishBarbGeReturnToLoot("Black axe GE mislukt -> weer looten");
                }
                return antiBan.varyDelay(rDelay(2200, 4000));
            }
        }

        if (!barbGeFlyRodBuyTried) {
            barbGeFlyRodBuyTried = true;
            if (!hasFlyFishingRod()) {
                int coins = getCoinCount();
                int startRod = Math.max(1, (int) Math.ceil(Prices.getItemPrice(ID_FLY_FISHING_ROD) * 1.20));
                int budget = coins - reserve;
                debug("Barb GE fly rod: coins=" + coins + ", reserve=" + reserve + ", start=" + startRod + ", budget=" + budget);
                if (budget < startRod) {
                    return finishBarbGeCannotAffordBarbToolkit("Fly fishing rod", startRod, budget);
                }
                paint.setCurrentStatus("Barb GE: Fly fishing rod");
                GeRestockHelper.RestockResult rr = GeRestockHelper.buyWithEscalation(
                        "Fly fishing rod", 1, startRod, () -> config.botEnabled());
                debug("Barb GE fly rod buy: " + rr);
                lastBarbGeActionMs = System.currentTimeMillis();
                if (rr != GeRestockHelper.RestockResult.SUCCESS) {
                    return finishBarbGeReturnToLoot("Fly fishing rod GE mislukt -> weer looten");
                }
                return antiBan.varyDelay(rDelay(2200, 4000));
            }
        }

        if (GrandExchange.canCollect()) {
            GrandExchange.collect(true);
            debug("Barb GE: final collect -> bank");
            lastBarbGeActionMs = System.currentTimeMillis();
            return antiBan.varyDelay(rDelay(1000, 1800));
        }

        if (config.barbLootGeBuyAirStaff() && !hasAirStaff()) {
            return finishBarbGeReturnToLoot("🛒 Geen Staff of air na GE → weer looten");
        }
        int mindFinish = maybeFinishBarbGeIfMindTargetNotMet(mindTargetCfg);
        if (mindFinish != BARB_GE_CONTINUE) return mindFinish;

        if (maybeStopBarbLootAndSwitch()) {
            closeGeEsc();
            return antiBan.varyDelay(rDelay(900, 1600));
        }

        closeGeEsc();
        markBarbGeCycleFinished("success");
        paint.setLastAntiBanAction("✓ Barb GE-trip klaar");
        debug("Barb GE flow finished");
        return antiBan.varyDelay(rDelay(1200, 2200));
    }


    private void refreshBarbGeBankTripPaint() {
        int th = config.barbLootGeAfterBanks();
        if (th <= 0 || barbGeStep > 0) {
            paint.setBarbLootGeBankTripProgress(0, 0);
            return;
        }
        paint.setBarbLootGeBankTripProgress(barbBankTripsSinceGe, th);
    }

    private void debug(String msg) {
        DebugLog.log("BarbLoot", msg);
    }

    private int rDelay(int a, int b) {
        return a + random.nextInt(Math.max(1, b - a));
    }

    private void humanBankPauseMs(int minInclusive, int maxExclusive) {
        int span = Math.max(1, maxExclusive - minInclusive);
        try {
            Thread.sleep(minInclusive + random.nextInt(span));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isLootFishName(String name) {
        return isRawLootFishName(name) || isCookedLootFishName(name);
    }

    /** Raw trout/salmon — prioriteit bij oprapen. */
    private boolean isRawLootFishName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("raw ") && (n.contains("trout") || n.contains("salmon"));
    }

    /** Gekookte / andere trout & salmon drops (geen «Raw »). */
    private boolean isCookedLootFishName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith("raw ")) return false;
        return n.contains("trout") || n.contains("salmon");
    }

    /** Niets vasthouden tijdens Barb loot: bij banken ook bijl, tinderbox en logs storten. */
    private boolean keepInInventory(String name) {
        return false;
    }

    private boolean hasAnyNonKeep() {
        return Inventory.getFirst(item ->
                item != null && item.getName() != null && !keepInInventory(item.getName())) != null;
    }

    /** Non-keep, maar logs tellen NIET mee als reden om meteen te banken. */
    private boolean hasAnyBankableNonKeepExcludingLogs() {
        return Inventory.getFirst(item -> {
            if (item == null || item.getName() == null) return false;
            String n = item.getName();
            if (keepInInventory(n)) return false;
            return !n.toLowerCase(Locale.ROOT).contains("log");
        }) != null;
    }

    private int inventoryLootFishCount() {
        return Inventory.getAll(item ->
                item != null && item.getName() != null && isLootFishName(item.getName())).size();
    }

    /** Startup-rommel: items die niet bij Barb-loot horen en ook geen keep-items zijn. */
    private boolean hasJunkNonFishItems() {
        return Inventory.getFirst(item -> {
            if (item == null || item.getName() == null) return false;
            String n = item.getName();
            return !keepInInventory(n) && !isLootFishName(n);
        }) != null;
    }

    private int maybeRetryPendingHop() {
        if (shouldAbortActions()) {
            pendingHopTargetWorld = -1;
            pendingHopStartWorld = -1;
            pendingHopRetryCount = 0;
            pendingHopRequestedAtMs = 0L;
            return 0;
        }
        if (pendingHopTargetWorld <= 0 || pendingHopRequestedAtMs <= 0L) {
            return 0;
        }
        try {
            Object wrapped = net.storm.sdk.game.Client.getClient().getWrapped();
            if (!(wrapped instanceof net.runelite.api.Client)) {
                return 0;
            }
            net.runelite.api.Client rl = (net.runelite.api.Client) wrapped;
            int cur = rl.getWorld();
            if (cur == pendingHopTargetWorld || cur != pendingHopStartWorld) {
                debug("hop confirm: start=" + pendingHopStartWorld + " now=" + cur + " target=" + pendingHopTargetWorld);
                pendingHopTargetWorld = -1;
                pendingHopStartWorld = -1;
                pendingHopRetryCount = 0;
                pendingHopRequestedAtMs = 0L;
                return 0;
            }
            long since = System.currentTimeMillis() - pendingHopRequestedAtMs;
            if (since < HOP_VERIFY_TIMEOUT_MS) {
                return 0;
            }
            if (pendingHopRetryCount >= 2) {
                debug("hop verify timeout, giving up after retries; current w" + cur + " target w" + pendingHopTargetWorld);
                paint.setLastAntiBanAction("🌍 Hop verificatie timeout — probeer later opnieuw");
                pendingHopTargetWorld = -1;
                pendingHopStartWorld = -1;
                pendingHopRetryCount = 0;
                pendingHopRequestedAtMs = 0L;
                return antiBan.varyDelay(rDelay(1500, 2600));
            }
            pendingHopRetryCount++;
            pendingHopRequestedAtMs = System.currentTimeMillis();
            AccountSwitchWorldHop.scheduleHopToWorld(rl, clientThread, pendingHopTargetWorld);
            paint.setLastAntiBanAction("🌍 Hop retry " + pendingHopRetryCount + " → w" + pendingHopTargetWorld);
            debug("hop retry " + pendingHopRetryCount + " start=" + pendingHopStartWorld + " current=" + cur + " target=" + pendingHopTargetWorld);
            return antiBan.varyDelay(rDelay(1100, 1800));
        } catch (Throwable t) {
            debug("maybeRetryPendingHop error: " + t.getMessage());
            return 0;
        }
    }

    private void updateWorldEntryTimestamp() {
        try {
            Object wrapped = net.storm.sdk.game.Client.getClient().getWrapped();
            if (!(wrapped instanceof net.runelite.api.Client)) {
                return;
            }
            net.runelite.api.Client rl = (net.runelite.api.Client) wrapped;
            int w = rl.getWorld();
            if (w <= 0) {
                return;
            }
            if (w != lastObservedWorldId) {
                lastObservedWorldId = w;
                enteredCurrentWorldAtMs = System.currentTimeMillis();
                debug("world changed -> w" + w + " grace timer reset");
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean near(WorldPoint p, WorldPoint q, int d) {
        return p != null && q != null && p.distanceTo(q) <= d;
    }

    /** OSRS vis-animaties (net/rod/harpoon e.d., ruime cluster). */
    private static boolean isOsrsFishingAnimation(int animId) {
        if (animId < 0) return false;
        if (animId >= 618 && animId <= 628) return true;
        return animId == 2891 || animId == 2892;
    }

    private static boolean looksLikeFishingSpotNpcName(String name) {
        if (name == null) return false;
        return name.toLowerCase(Locale.ROOT).contains("fishing spot");
    }

    /** Andere speler vist (animatie of interact met Fishing spot / Rod spot NPC). */
    private boolean isPlayerActivelyFishing(IPlayer p) {
        if (p == null) return false;
        if (isOsrsFishingAnimation(p.getAnimation())) return true;
        try {
            if (!p.isInteracting()) return false;
            if (p.getInteracting() instanceof INPC) {
                INPC npc = (INPC) p.getInteracting();
                return looksLikeFishingSpotNpcName(npc.getName());
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Minstens één andere speler binnen {@link #NEAR_ACTIVITY} van Barb center die aan het vissen is. */
    private boolean hasOtherPlayerFishingNearBarb(IPlayer local) {
        if (local == null) return false;
        try {
            List<IPlayer> others = Players.getAll(p ->
                    p != null
                            && !p.equals(local)
                            && p.getWorldLocation() != null
                            && p.getWorldLocation().distanceTo(BARB_LOOT_CENTER) <= NEAR_ACTIVITY);
            if (others == null || others.isEmpty()) return false;
            for (IPlayer p : others) {
                if (isPlayerActivelyFishing(p)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private ITileItem pickLootFishPreferRaw(WorldPoint me, double maxDistFromPlayer) {
        if (me == null) return null;
        Predicate<ITileItem> inReach = item -> {
            if (item == null || item.getName() == null || item.getWorldLocation() == null) return false;
            if (isFishTileExcluded(item.getWorldLocation())) return false;
            if (!isLootFishName(item.getName())) return false;
            if (item.getWorldLocation().distanceTo(BARB_LOOT_CENTER) > LOOT_RADIUS + 2) return false;
            return me.distanceTo(item.getWorldLocation()) <= maxDistFromPlayer;
        };
        if (config.lootOnlyOwn()) {
            Comparator<ITileItem> byDist = Comparator.comparingInt(a -> (int) me.distanceTo(a.getWorldLocation()));
            ITileItem raw = TileItems.getAllMine().stream()
                    .filter(inReach::test)
                    .filter(i -> isRawLootFishName(i.getName()))
                    .min(byDist)
                    .orElse(null);
            if (raw != null) return raw;
            return TileItems.getAllMine().stream()
                    .filter(inReach::test)
                    .min(byDist)
                    .orElse(null);
        }
        ITileItem raw = TileItems.getNearest(item -> inReach.test(item) && isRawLootFishName(item.getName()));
        if (raw != null) return raw;
        return TileItems.getNearest(inReach::test);
    }

    private ITileItem nearestLootFishInBarbAreaPreferRaw() {
        Predicate<ITileItem> inArea = item -> {
            if (item == null || item.getName() == null || item.getWorldLocation() == null) return false;
            if (isFishTileExcluded(item.getWorldLocation())) return false;
            if (!isLootFishName(item.getName())) return false;
            return item.getWorldLocation().distanceTo(BARB_LOOT_CENTER) <= LOOT_RADIUS + 2;
        };
        if (config.lootOnlyOwn()) {
            IPlayer local = Players.getLocal();
            WorldPoint me = local != null && local.getWorldLocation() != null ? local.getWorldLocation() : BARB_LOOT_CENTER;
            Comparator<ITileItem> byDist = Comparator.comparingInt(a -> (int) me.distanceTo(a.getWorldLocation()));
            ITileItem raw = TileItems.getAllMine().stream()
                    .filter(inArea::test)
                    .filter(i -> isRawLootFishName(i.getName()))
                    .min(byDist)
                    .orElse(null);
            if (raw != null) return raw;
            return TileItems.getAllMine().stream()
                    .filter(inArea::test)
                    .min(byDist)
                    .orElse(null);
        }
        ITileItem raw = TileItems.getNearest(item -> inArea.test(item) && isRawLootFishName(item.getName()));
        if (raw != null) return raw;
        return TileItems.getNearest(inArea::test);
    }

    private ITileItem findGroundFish(IPlayer local) {
        if (local == null) return null;
        WorldPoint me = local.getWorldLocation();
        if (me == null) return null;
        if (me.distanceTo(BARB_LOOT_CENTER) > NEAR_ACTIVITY) return null;
        final long now = System.currentTimeMillis();
        if (now < nextFishPickupAllowedMs) return null;

        return pickLootFishPreferRaw(me, PICKUP_REACH);
    }

    private void scheduleNextFishPickupGap() {
        lastPickupMs = System.currentTimeMillis();
        nextFishPickupAllowedMs = lastPickupMs + rDelay(320, 2100);
    }

    private int walkTowards(WorldPoint dest, String status) {
        long now = System.currentTimeMillis();
        if (!TravelWalkHelper.reclickReady(lastTravelClick, config)) {
            return TravelWalkHelper.shortWaitMs(random);
        }
        MovementHelper.walkTo(dest);
        lastTravelClick = now;
        paint.setCurrentStatus(status);
        return antiBan.varyDelay(TravelWalkHelper.postClickDelayMs(config, random));
    }

    private int handleBankEdge(IPlayer local) {
        if (shouldAbortActions()) {
            return antiBan.varyDelay(rDelay(250, 500));
        }
        WorldPoint me = local.getWorldLocation();
        if (Bank.isOpen()) {
            boolean depositedLootFish = false;
            if (hasAnyNonKeep()) {
                humanBankPauseMs(500, 1100);
                int guard = 0;
                while (!shouldAbortActions() && hasAnyNonKeep() && guard++ < 48) {
                    IInventoryItem next = Inventory.getFirst(it ->
                            it != null && it.getName() != null && !keepInInventory(it.getName()));
                    if (next == null) break;
                    if (next.getId() == GENIE_LAMP_ITEM_ID) {
                        humanBankPauseMs(120, 240);
                        continue;
                    }
                    String n = next.getName();
                    if (isLootFishName(n)) depositedLootFish = true;
                    Bank.depositAll(n);
                    if (hasAnyNonKeep()) humanBankPauseMs(420, 1100);
                }
            }
            humanBankPauseMs(220, 550);
            Bank.close();
            if (depositedLootFish) {
                int th = config.barbLootGeAfterBanks();
                if (th > 0) {
                    long sinceGeFinish = System.currentTimeMillis() - lastBarbGeCycleFinishMs;
                    if (lastBarbGeCycleFinishMs > 0 && sinceGeFinish < BARB_GE_POST_FINISH_TRIP_COOLDOWN_MS) {
                        debug("Barb GE trip counter cooldown active (" + sinceGeFinish + "ms/" + BARB_GE_POST_FINISH_TRIP_COOLDOWN_MS + "ms) -> skip increment");
                    } else {
                        barbBankTripsSinceGe++;
                        debug("Barb loot bank trip " + barbBankTripsSinceGe + "/" + th);
                        if (barbBankTripsSinceGe >= th) {
                            barbBankTripsSinceGe = 0;
                            barbGeStep = 1;
                            paint.setLastAntiBanAction("🛒 Barb loot: start GE na " + th + " bank trip(s)");
                            debug("Barb GE trigger reached -> step=1");
                        }
                    }
                }
            }
            paint.setLastAntiBanAction("✓ Loot bank (Edge)");
            return antiBan.varyDelay(rDelay(600, 1200));
        }

        if (near(me, EDGEVILLE_BANK_AREA, BANK_PROX)) {
            if (BankHelper.tryOpenFullBank()) {
                return antiBan.varyDelay(rDelay(800, 1600));
            }
            if (BankHelper.interactIfNearby()) {
                return antiBan.varyDelay(rDelay(700, 1300));
            }
            return antiBan.varyDelay(rDelay(500, 900));
        }
        return walkTowards(EDGEVILLE_BANK_AREA, "🏦 → Edgeville bank");
    }

    private int hopF2pWorld() {
        if (shouldAbortActions()) {
            return antiBan.varyDelay(rDelay(250, 500));
        }
        try {
            Object wrapped = net.storm.sdk.game.Client.getClient().getWrapped();
            if (!(wrapped instanceof net.runelite.api.Client)) {
                paint.setLastAntiBanAction("🌍 Hop: geen RuneLite client");
                return 15_000;
            }
            net.runelite.api.Client rl = (net.runelite.api.Client) wrapped;
            int cur = rl.getWorld();
            int myTotal = getAccountTotalLevel();
            net.runelite.api.World[] wlist = rl.getWorldList();
            net.runelite.api.World dest = pickAccessibleF2pWorld(rl);
            if (dest == null) {
                paint.setLastAntiBanAction("🌍 Geen F2P wereld — lijst leeg? Handmatig / wacht even");
                debug("hopF2pWorld: geen candidate; worldList="
                        + (wlist == null ? "null" : ("len=" + wlist.length + " cur=" + cur)));
                waitUntilMs = 0;
                return 20_000;
            }
            final int targetId = dest.getId();
            AccountSwitchWorldHop.scheduleHopToWorld(rl, clientThread, targetId);
            pendingHopStartWorld = cur;
            pendingHopTargetWorld = targetId;
            pendingHopRetryCount = 0;
            pendingHopRequestedAtMs = System.currentTimeMillis();

            paint.setLastAntiBanAction("🌍 F2P hop → w" + targetId + " (lvl " + myTotal + ")");
            debug("hop scheduled w" + targetId + " was=" + cur + " via AccountSwitchWorldHop");
            waitUntilMs = 0;
            return 15_000;
        } catch (Throwable t) {
            debug("world hop failed: " + t.getMessage());
            paint.setLastAntiBanAction("🌍 F2P hop faalde — open wereldwisselaar handmatig");
            waitUntilMs = 0;
            return 20_000;
        }
    }

    /** Geen woodcutting meer: alleen lichte idle rond de spot tot de wachttijd voorbij is. */
    private int waitIdleWhileFishingActivity(IPlayer local) {
        if (local.isMoving()) {
            return antiBan.varyDelay(rDelay(400, 900));
        }
        if (random.nextInt(14) == 0 && TravelWalkHelper.reclickReady(lastTravelClick, config)) {
            int jx = BARB_LOOT_CENTER.getX() + random.nextInt(7) - 3;
            int jy = BARB_LOOT_CENTER.getY() + random.nextInt(7) - 3;
            MovementHelper.walkTo(new WorldPoint(jx, jy, 0));
            lastTravelClick = System.currentTimeMillis();
            paint.setCurrentStatus("Loot-wacht: rond spot");
            return antiBan.varyDelay(rDelay(700, 1400));
        }
        paint.setCurrentStatus("Loot-wacht (geen WC)");
        return antiBan.varyDelay(rDelay(1000, 2400));
    }

    public int loop() {
        try {
            if (shouldAbortActions()) {
                return antiBan.varyDelay(rDelay(250, 500));
            }
            IPlayer local = Players.getLocal();
            if (local == null) return 1000;

            updateWorldEntryTimestamp();

            if (maybeStopBarbLootAndSwitch()) {
                return antiBan.varyDelay(rDelay(700, 1200));
            }

            refreshBarbGeBankTripPaint();
            // Safety: mocht teller ooit blijven hangen op threshold terwijl GE-step uit staat.
            int geTh = config.barbLootGeAfterBanks();
            if (geTh > 0 && barbGeStep == 0 && barbBankTripsSinceGe >= geTh) {
                debug("Barb GE safety reset: trip counter stuck at " + barbBankTripsSinceGe + "/" + geTh + " while step=0");
                barbBankTripsSinceGe = 0;
            }
            int hopRetryDelay = maybeRetryPendingHop();
            if (hopRetryDelay > 0) {
                return hopRetryDelay;
            }

            if (config.barbLootGeAfterBanks() > 0 && barbGeStep > 0) {
                return handleBarbGeFlow(local);
            }

            ITileItem fish = findGroundFish(local);
            if (fish != null && !Inventory.isFull()) {
                if (local.isMoving()) {
                    return antiBan.varyDelay(rDelay(280, 600));
                }
                waitUntilMs = 0;
                fish.pickup();
                scheduleNextFishPickupGap();
                paint.setCurrentStatus("🐟 Loot: vis");
                return antiBan.varyDelay(rDelay(650, 1700));
            }

            WorldPoint me = local.getWorldLocation();
            boolean atLootSpot = near(me, BARB_LOOT_CENTER, SPOT_PROX);

            if (Inventory.isFull()) {
                // Probeer eerst 1 hap te eten om plek te maken voor de vis op de grond.
                // Alleen als toggle aan + HP niet vol + er ligt eet-waardige loot.
                if (fish != null) {
                    int eatDelay = EatForLootSpaceHelper.tryEatForSpace(
                            config, false, 8, null, this::debug);
                    if (eatDelay > 0) {
                        paint.setCurrentStatus("🍗 Eet voor loot-ruimte");
                        return antiBan.varyDelay(eatDelay);
                    }
                }
                return handleBankEdge(local);
            }

            int fishBankThreshold = Math.max(0, config.barbLootBankFishCount());
            if (fishBankThreshold > 0) {
                int fishInInv = inventoryLootFishCount();
                if (fishInInv >= fishBankThreshold) {
                    debug("Barb loot fish-threshold hit: " + fishInInv + "/" + fishBankThreshold + " -> bank");
                    return handleBankEdge(local);
                }
            }

            // Nieuw account met starter-troep: eerst banken i.p.v. idle/hop-loop bij Barb spot.
            if (hasJunkNonFishItems()) {
                return handleBankEdge(local);
            }

            if (!atLootSpot && hasAnyBankableNonKeepExcludingLogs()) {
                return handleBankEdge(local);
            }

            if (!atLootSpot) {
                return walkTowards(BARB_LOOT_CENTER, "→ Barb fishing (loot)");
            }

            ITileItem fishInArea = nearestLootFishInBarbAreaPreferRaw();
            if (fishInArea != null && me != null) {
                waitUntilMs = 0;
                long nowPick = System.currentTimeMillis();
                if (nowPick < nextFishPickupAllowedMs) {
                    return antiBan.varyDelay(rDelay(400, 900));
                }
                int fd = me.distanceTo(fishInArea.getWorldLocation());
                if (fd > PICKUP_REACH - 1) {
                    if (TravelWalkHelper.reclickReady(lastTravelClick, config)) {
                        MovementHelper.walkTo(fishInArea.getWorldLocation());
                        lastTravelClick = System.currentTimeMillis();
                    }
                    paint.setCurrentStatus("🐟 → vis (loot)");
                    return antiBan.varyDelay(rDelay(450, 900));
                }
                if (local.isMoving()) {
                    return antiBan.varyDelay(rDelay(280, 600));
                }
                fishInArea.pickup();
                scheduleNextFishPickupGap();
                paint.setCurrentStatus("🐟 Loot: vis");
                return antiBan.varyDelay(rDelay(600, 1600));
            }

            long nowFishers = System.currentTimeMillis();
            if (!hasOtherPlayerFishingNearBarb(local)) {
                long inWorldMs = enteredCurrentWorldAtMs > 0 ? (nowFishers - enteredCurrentWorldAtMs) : Long.MAX_VALUE;
                if (inWorldMs < WORLD_LOOT_VISIBILITY_GRACE_MS) {
                    long leftSec = Math.max(1, (WORLD_LOOT_VISIBILITY_GRACE_MS - inWorldMs) / 1000);
                    paint.setCurrentStatus("⏳ Nieuwe wereld: wacht op drops (" + leftSec + "s)");
                    debug("no-fisher hop suppressed by world-grace: " + inWorldMs + "ms/" + WORLD_LOOT_VISIBILITY_GRACE_MS + "ms");
                    return antiBan.varyDelay(rDelay(1200, 2200));
                }
                if (nowFishers - lastNoFishNearbyFisherHopMs >= NO_FISHERS_NEARBY_HOP_COOLDOWN_MS) {
                    lastNoFishNearbyFisherHopMs = nowFishers;
                    waitUntilMs = 0;
                    paint.setLastAntiBanAction("🌍 Geen vissers nabij spot → F2P hop");
                    paint.setCurrentStatus("🌍 Zoek drukkere wereld (loot)…");
                    return hopF2pWorld();
                }
                return antiBan.varyDelay(rDelay(1800, 3500));
            }

            long now = System.currentTimeMillis();
            if (waitUntilMs == 0) {
                waitUntilMs = now + WAIT_MIN_MS + random.nextInt((int) (WAIT_MAX_MS - WAIT_MIN_MS));
                paint.setLastAntiBanAction("Geen vis — wacht " + ((waitUntilMs - now) / 60000) + " min → hop");
            }

            if (now < waitUntilMs) {
                return waitIdleWhileFishingActivity(local);
            }

            waitUntilMs = 0;
            return hopF2pWorld();
        } catch (Exception e) {
            debug("loop error: " + e.getMessage());
            paint.setCurrentStatus("⚠ Loot fout");
            return 3000;
        }
    }

    private boolean shouldAbortActions() {
        return config == null || !config.botEnabled();
    }
}
