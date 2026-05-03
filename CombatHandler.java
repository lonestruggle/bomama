package com.combatbot;

import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.storm.api.domain.actors.INPC;
import net.storm.api.domain.actors.IPlayer;
import net.storm.api.domain.items.IInventoryItem;
import net.storm.api.domain.tiles.ITileItem;
import net.storm.api.movement.pathfinder.model.BankLocation;
import net.storm.sdk.entities.NPCs;
import net.storm.sdk.entities.Players;
import net.storm.sdk.entities.TileItems;
import net.storm.sdk.game.Combat;
import net.storm.sdk.game.Skills;
import net.storm.sdk.items.Bank;
import net.storm.sdk.items.Equipment;
import net.storm.sdk.items.Inventory;
import net.storm.sdk.movement.Movement;
import com.combatbot.CombatBotConfig.CombatGeFoodType;
import com.combatbot.CenterManager;
import net.storm.sdk.movement.Reachable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Combat handler - beheert alle combat, loot, eat, bury bones en bank logica.
 *
 * Loot: "rune" keyword wordt automatisch uitgebreid naar alle rune types.
 * Nieuw: lootByMinValue — pakt items op boven een minimale HA waarde.
 * Nieuw: configurable attack delay (attackDelayMin/Max).
 * Nieuw: loot pickup cooldown tegen te snel klikken.
 */
public class CombatHandler {

    private final CombatBotConfig config;
    private final AntiBan antiBan;
    private final CombatBotPaint paint;

    private TileMarkerManager tileMarkerManager;
    private SafespotHandler safespotHandler;

    private WorldPoint fightArea;
    private int centerRadius = 0;
    private WorldPoint centerWalkTarget = null;

    private final Random random = new Random();

    private boolean targetWasAlive = false;
    private long lootWindowEnd = 0;

    private int lastAttackedNpcIndex = -1;
    private int lastDeadNpcIndex = -1;

    private int killsSinceLastLoot = 0;

    /** Effective thresholds met ±1 random variance (herberekend elk cycle). */
    private int effectiveBuryThreshold = -1;
    private int effectiveLootKills = -1;

    private final UniversalBankingManager bankingManager = new UniversalBankingManager();

    private long lastAttackClickTime = 0;
    private static final long ATTACK_CLICK_COOLDOWN_MS = 1800;

    // Loot pickup cooldown — voorkom te snel klikken op loot
    private long lastLootPickupTime = 0;
    private static final long LOOT_PICKUP_COOLDOWN_MS = 2400;

    // Rune/arrow failsafe: detecteer "not enough runes" of "no ammo" chat berichten
    private boolean noRunesDetected = false;
    private boolean noAmmoDetected = false;
    private long noRunesDetectedTime = 0;
    /** Laatste OSRS-gamebericht "geen ammo in quiver" — Equipment API kan daar kort naast lopen. */
    private long lastRangedEmptyQuiverGameMessageMs;

    // Arrow pickup tracking
    private int killsSinceArrowPickup = 0;
    private int nextArrowPickupKills = 0;

    private static final int LOCATION_THRESHOLD = 5;
    private static final int POST_KILL_WAIT_MS = 1200;

    private static final List<String> KNOWN_FOOD_ACTIONS = Arrays.asList("Eat", "Drink");

    private static final String[] BONE_NAMES = {
            "Bones", "Big bones", "Babydragon bones", "Dragon bones", "Dagannoth bones",
            "Wyvern bones", "Lava dragon bones", "Superior dragon bones", "Wyrm bones",
            "Drake bones", "Hydra bones", "Jogre bones", "Zogre bones", "Shaikahan bones",
            "Fayrg bones", "Raurg bones", "Ourg bones"
    };

    private static final String[] ASH_NAMES = {
            "Ashes", "Fiendish ashes", "Vile ashes", "Malicious ashes",
            "Abyssal ashes", "Infernal ashes"
    };

    // Alle bekende rune namen in OSRS
    private static final String[] ALL_RUNE_NAMES = {
            "Air rune", "Water rune", "Earth rune", "Fire rune", "Mind rune",
            "Body rune", "Cosmic rune", "Chaos rune", "Nature rune", "Law rune",
            "Death rune", "Astral rune", "Blood rune", "Soul rune", "Wrath rune",
            "Mist rune", "Dust rune", "Mud rune", "Smoke rune", "Steam rune", "Lava rune"
    };

    // Bekende ranged wapens en ammo
    private static final String[] RANGED_WEAPONS = {
            "shortbow", "longbow", "crossbow", "ballista", "blowpipe", "bow"
    };
    private static final String[] RANGED_AMMO = {
            "arrow", "bolt", "dart", "javelin", "knife", "thrownaxe", "chinchompa", "brutal"
    };
    private static final String[] MAGIC_WEAPONS = {
            "staff", "wand", "trident", "sanguinesti", "tumeken"
    };

    /** Flag: GE+bank hebben geen food → Combat moet door plugin gestopt/uit rotatie. */
    private boolean outOfFoodNoGeRestock = false;

    /** Panel-uitloggen: geen nieuwe aanvallen; wel loot/afwachten tot combat voorbij. */
    private boolean suppressNewAttacksForLogout = false;

    /** Melee wapens met Attack level vereisten (gesorteerd hoog→laag). */
    private static final Object[][] LEVELED_MELEE_WEAPONS = {
            {"Rune scimitar", 40}, {"Rune sword", 40},
            {"Adamant scimitar", 30}, {"Adamant sword", 30},
            {"Mithril scimitar", 20}, {"Mithril sword", 20},
            {"Steel scimitar", 5}, {"Steel sword", 5},
            {"Iron scimitar", 1}, {"Iron sword", 1}, {"Iron dagger", 1},
            {"Bronze scimitar", 1}, {"Bronze sword", 1}, {"Bronze dagger", 1},
    };

    /** Ranged wapens met Ranged level vereisten (gesorteerd hoog→laag). */
    private static final Object[][] LEVELED_RANGED_WEAPONS = {
            {"Magic shortbow", 50}, {"Magic longbow", 50},
            {"Maple shortbow", 30}, {"Maple longbow", 30},
            {"Willow shortbow", 20}, {"Willow longbow", 20},
            {"Oak shortbow", 5}, {"Oak longbow", 5},
            {"Shortbow", 1}, {"Longbow", 1},
    };

    /** Magic wapens met Magic level vereisten (gesorteerd hoog→laag). */
    private static final Object[][] LEVELED_MAGIC_WEAPONS = {
            {"Staff of fire", 1}, {"Staff of water", 1}, {"Staff of earth", 1}, {"Staff of air", 1},
    };

    /** Optionele debug helper voor Combat — logt naar de Debug tab. */
    private void debug(String msg) {
        DebugLog.log("Combat", msg);
    }

    /** Reset lichte runtime-state zodat de handler "schoon" opnieuw kan starten. */
    public void resetState() {
        targetWasAlive = false;
        lootWindowEnd = 0;
        lastAttackedNpcIndex = -1;
        lastDeadNpcIndex = -1;
        killsSinceLastLoot = 0;
        lastAttackClickTime = 0;
        lastLootPickupTime = 0;
        noRunesDetected = false;
        noAmmoDetected = false;
        noRunesDetectedTime = 0;
        lastRangedEmptyQuiverGameMessageMs = 0L;
        killsSinceArrowPickup = 0;
        nextArrowPickupKills = 0;
        effectiveBuryThreshold = -1;
        effectiveLootKills = -1;
    }

    /**
     * Voeg ±1 random variance toe aan een threshold (alleen als base >= 3).
     * Voorkomt dat het resultaat onder 1 komt.
     */
    private int applyVariance(int base) {
        if (base < 3) return base;
        int variance = random.nextInt(3) - 1; // -1, 0, of +1
        return Math.max(1, base + variance);
    }

    private enum State {
        FIGHTING, LOOTING, EATING, WALKING_TO_BANK, BANKING, WALKING_TO_FIGHT,
        POST_KILL_WAIT, WAITING_TO_LOOT, RETURNING_TO_CENTER, PAUSED_NO_FOOD,
        PAUSED_NO_AMMO, SAFESPOT, BURYING_BONES, PICKING_UP_ARROWS
    }

    public CombatHandler(CombatBotConfig config, AntiBan antiBan, CombatBotPaint paint, WorldPoint fightArea) {
        this.config = config;
        this.antiBan = antiBan;
        this.paint = paint;
        this.fightArea = fightArea;
        this.nextArrowPickupKills = randomDelay(config.pickupArrowsMinKills(), Math.max(config.pickupArrowsMinKills() + 1, config.pickupArrowsMaxKills()));
    }

    /**
     * Ontvang chat berichten van de plugin om rune/arrow problemen te detecteren.
     * Zoekt naar: "you do not have enough X runes to cast this spell"
     * en soortgelijke ranged berichten.
     */
    public void onChatMessage(String message) {
        if (message == null) return;
        String lower = message.toLowerCase();

        // Detecteer "you do not have enough X runes" of "you don't have enough runes"
        if (lower.contains("do not have enough") && lower.contains("rune")) {
            noRunesDetected = true;
            noRunesDetectedTime = System.currentTimeMillis();
            chatLog("⚠ Geen runes meer gedetecteerd! Combat gestopt.");
        }
        // Detecteer "you don't have enough runes to cast that"
        if (lower.contains("enough runes to cast")) {
            noRunesDetected = true;
            noRunesDetectedTime = System.currentTimeMillis();
            chatLog("⚠ Geen runes meer gedetecteerd! Combat gestopt.");
        }
        // Detecteer ranged ammo problemen — inventory/bank/GE lossen dit op (geen harde stop)
        if (RangedAmmoKit.isRangedEmptyQuiverGameMessage(message)) {
            noAmmoDetected = true;
            lastRangedEmptyQuiverGameMessageMs = System.currentTimeMillis();
            chatLog("⚠ Geen ammo in quiver — eerst inventory/bank, anders GE.");
        }
    }

    private void chatLog(String message) {
        try {
            net.runelite.api.Client rlClient = net.storm.sdk.game.Client.getClient().getWrapped();
            if (rlClient != null) {
                rlClient.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "[Combat] " + message, null);
            }
        } catch (Exception e) {
            System.out.println("[CombatChat] " + message);
        }
    }

    public void setTileMarkerManager(TileMarkerManager manager) {
        this.tileMarkerManager = manager;
        updateSafespotFromMarkers();
    }

    public void updateSafespotFromMarkers() {
        if (tileMarkerManager == null) return;
        java.util.List<WorldPoint> allSpots = tileMarkerManager.getAllSafespots();
        if (allSpots == null || allSpots.isEmpty()) {
            safespotHandler = null;
            return;
        }

        // Filter: alleen safespots binnen de center radius
        java.util.List<WorldPoint> spots;
        if (fightArea != null && centerRadius > 0) {
            spots = allSpots.stream()
                    .filter(sp -> Math.abs(sp.getX() - fightArea.getX()) <= centerRadius
                            && Math.abs(sp.getY() - fightArea.getY()) <= centerRadius
                            && sp.getPlane() == fightArea.getPlane())
                    .collect(java.util.stream.Collectors.toList());
            debug("Safespots gefilterd: " + spots.size() + "/" + allSpots.size() + " binnen center radius " + centerRadius);
        } else {
            spots = allSpots;
        }

        if (spots.isEmpty()) {
            debug("Geen safespots binnen center radius — safespot uitgeschakeld");
            safespotHandler = null;
            return;
        }

        if (safespotHandler == null) {
            safespotHandler = new SafespotHandler(spots.get(0), config.attackRange());
            safespotHandler.setSafespots(spots);
        } else {
            safespotHandler.setSafespots(spots);
        }
        debug("Safespots geladen: " + spots.size() + " spots (binnen radius)");
    }

    public void setActiveCenter(WorldPoint center, int radius) {
        this.fightArea = center;
        this.centerRadius = radius;
        this.centerWalkTarget = null;
        if (center == null) noCenterGearFetched = false;
    }

    public void setSuppressNewAttacksForLogout(boolean suppress) {
        this.suppressNewAttacksForLogout = suppress;
    }

    /** Speler in melee/range/mage interactie (zelfde als interne combat-detectie). */
    public boolean isInPlayerCombatInteraction() {
        return isInCombat();
    }

    /**
     * Nog loot of post-kill venster vóór veilig uitloggen (panel).
     */
    public boolean hasPendingLootBeforePanelLogout() {
        if (isInCombat()) {
            return false;
        }
        if (lootWindowEnd > 0 && System.currentTimeMillis() < lootWindowEnd) {
            return true;
        }
        return hasLootNearby() || hasSpecialLootNearby();
    }

    public int loop() {
        try {
            if (shouldAbortActions()) {
                return 300;
            }
            State state = determineState();

            switch (state) {
                case EATING:
                    paint.setCurrentStatus("🍖 Eten...");
                    return handleEating();
                case BURYING_BONES:
                    paint.setCurrentStatus("🦴 Bones begraven / Ashes verstrooien...");
                    return handleBuryingBones();
                case PAUSED_NO_AMMO:
                    paint.setCurrentStatus("⛔ Geen ammo/runes — naar bank");
                    return handleWalkingToBank();
                case PICKING_UP_ARROWS:
                    paint.setCurrentStatus("🏹 Arrows oppakken...");
                    return handlePickupArrows();
                case PAUSED_NO_FOOD:
                    paint.setCurrentStatus("⛔ Geen food - wachten...");
                    return 2000;
                case POST_KILL_WAIT:
                    paint.setCurrentStatus("💀 Kill! Wachten op loot...");
                    return POST_KILL_WAIT_MS;
                case WAITING_TO_LOOT:
                    paint.setCurrentStatus("⏳ Loot delay (" + killsSinceLastLoot + "/" + config.lootDelayKills() + " kills)");
                    return 600;
                case LOOTING:
                    paint.setCurrentStatus("💰 Loot oppakken");
                    return handleLooting();
                case WALKING_TO_BANK:
                    paint.setCurrentStatus("→ Lopen naar bank");
                    return handleWalkingToBank();
                case BANKING:
                    paint.setCurrentStatus("🏦 Aan het banken");
                    return handleBanking();
                case RETURNING_TO_CENTER:
                    paint.setCurrentStatus("↩ Terug naar center");
                    return handleReturnToCenter();
                case WALKING_TO_FIGHT:
                    paint.setCurrentStatus("→ Lopen naar combat spot");
                    return handleWalkingToFight();
                case SAFESPOT:
                    paint.setCurrentStatus("🛡 Safespot modus");
                    return handleSafespot();
                case FIGHTING:
                default:
                    paint.setCurrentStatus("⚔ Vechten");
                    return handleFighting();
            }
        } catch (Exception e) {
            paint.setCurrentStatus("⚠ Fout: " + e.getMessage());
            return 2000;
        }
    }

    // Geen center geselecteerd: toch eerst naar bank voor food/gear
    private boolean noCenterGearFetched = false;
    /** Eenmaal gestart met begraven: doorgaan tot alle bones/ashes op zijn (niet stoppen bij < minBatch). */
    private boolean buryingBatchStarted = false;

    private State determineState() {
        if (shouldEat() && hasFoodToEat()) return State.EATING;
        // Bones/ashes: start bij min. X stuks of volle inv, daarna ALLE gebruiken (één voor één)
        if (config.buryBones()) {
            int count = countBuryableItems();
            if (count == 0) {
                buryingBatchStarted = false;
                effectiveBuryThreshold = -1; // reset voor volgende cycle
            } else {
                // Bereken effective threshold met ±1 variance (eenmalig per cycle)
                if (effectiveBuryThreshold < 0) {
                    int base = Math.max(1, config.buryBonesMinBatch());
                    effectiveBuryThreshold = applyVariance(base);
                }
                if (Inventory.isFull() || count >= effectiveBuryThreshold) {
                    buryingBatchStarted = true;
                }
            }
            if (buryingBatchStarted && count > 0) return State.BURYING_BONES;
        }

        // Geen combat center: altijd eerst één keer banken voor food (en evt. gear)
        if (fightArea == null && !noCenterGearFetched) {
            return Bank.isOpen() ? State.BANKING : State.WALKING_TO_BANK;
        }

        // Chat-gebaseerde rune/ammo detectie
        if (noRunesDetected || noAmmoDetected) {
            // Lege quiver-gamebericht + stacks in rugzak: eerst equippen in combat-loop, niet meteen bank
            if (noAmmoDetected && !noRunesDetected && shouldDeferAmmoBankForInventoryEquip()) {
                noAmmoDetected = false;
            } else {
                return Bank.isOpen() ? State.BANKING : State.WALKING_TO_BANK;
            }
        }

        // Ammo/rune check
        if (!hasAmmoForCombatStyle()) {
            return Bank.isOpen() ? State.BANKING : State.WALKING_TO_BANK;
        }

        // Arrow pickup check
        if (config.pickupArrows() && !isInCombat() && shouldPickupArrows()) {
            return State.PICKING_UP_ARROWS;
        }

        if (!hasFoodToEat()) {
            if (config.bankWhenNoFood()) {
                return Bank.isOpen() ? State.BANKING : State.WALKING_TO_BANK;
            } else if (config.disableCombatNoFood()) {
                return State.PAUSED_NO_FOOD;
            }
        }

        // Banking voor gelootte items: inv vol, minimaal 5 non-food items
        if (config.bankLootedItems() && Inventory.isFull() && countLootedItems() >= 5) {
            return Bank.isOpen() ? State.BANKING : State.WALKING_TO_BANK;
        }

        if (lootWindowEnd > 0 && System.currentTimeMillis() < lootWindowEnd) {
            return State.POST_KILL_WAIT;
        }

        // Panel-uitloggen: loot eerst, geen kill-delay-blokkade
        if (suppressNewAttacksForLogout && !isInCombat()) {
            if (hasSpecialLootNearby()) {
                return State.LOOTING;
            }
            if (hasLootNearby()) {
                return State.LOOTING;
            }
        }

        // Loot alleen als we niet in gevecht zijn
        if (hasLootNearby() && !isInCombat()) {
            // Speciale loot altijd direct oppakken
            if (hasSpecialLootNearby()) return State.LOOTING;
            // Loot delay: bot blijft killen tot kill-threshold bereikt
            if (config.lootDelayEnabled()) {
                // Bereken effective kill threshold met ±1 variance (eenmalig per cycle)
                if (effectiveLootKills < 0) {
                    int base = config.lootDelayKills();
                    effectiveLootKills = applyVariance(base);
                }
                if (effectiveLootKills > 0 && killsSinceLastLoot < effectiveLootKills) {
                    // Niet genoeg kills → ga door met vechten (NIET idle wachten)
                    paint.setCurrentStatus("⚔ Vechten (loot delay: " + killsSinceLastLoot + "/" + effectiveLootKills + " kills)");
                    return State.FIGHTING;
                }
            }
            return State.LOOTING;
        }

        if (config.safespotEnabled() && safespotHandler != null && tileMarkerManager != null
                && !tileMarkerManager.getAllSafespots().isEmpty()) {
            return State.SAFESPOT;
        }

        if (fightArea != null && isOutsideAttackRange()) {
            return State.RETURNING_TO_CENTER;
        }

        return State.FIGHTING;
    }

    /**
     * Tel hoeveel items in de inventory GEEN food zijn (= gelootte items).
     * Items die eetbaar/drinkbaar zijn tellen niet mee.
     */
    private int countLootedItems() {
        int nonFoodCount = 0;
        var items = Inventory.getAll();
        if (items == null) return 0;
        for (var item : items) {
            if (item == null || item.getName() == null) continue;
            // Skip food items
            String[] actions = item.getActions();
            boolean isFood = false;
            if (actions != null) {
                for (String a : actions) {
                    if (a != null && (a.equalsIgnoreCase("Eat") || a.equalsIgnoreCase("Drink"))) {
                        isFood = true;
                        break;
                    }
                }
            }
            if (!isFood) nonFoodCount++;
        }
        return nonFoodCount;
    }

    // ===================== BURY BONES / SCATTER ASHES =====================

    private boolean hasBuryableItems() {
        return countBuryableItems() > 0;
    }

    private int countBuryableItems() {
        var allItems = Inventory.getAll(item -> {
            if (item == null || item.getName() == null) return false;
            String name = item.getName();
            for (String bone : BONE_NAMES) if (bone.equalsIgnoreCase(name)) return true;
            for (String ash : ASH_NAMES) if (ash.equalsIgnoreCase(name)) return true;
            return false;
        });
        return allItems != null ? allItems.size() : 0;
    }

    /** Menselijk tempo: min/max ms tussen elke bury/scatter actie. Iets sneller maar nog variabel. */
    private static final int BURY_DELAY_MIN_MS = 900;
    private static final int BURY_DELAY_MAX_MS = 2100;

    private int handleBuryingBones() {
        for (String bone : BONE_NAMES) {
            IInventoryItem item = Inventory.getFirst(bone);
            if (item != null) {
                item.interact("Bury");
                return antiBan.varyDelay(randomDelay(BURY_DELAY_MIN_MS, BURY_DELAY_MAX_MS));
            }
        }
        for (String ash : ASH_NAMES) {
            IInventoryItem item = Inventory.getFirst(ash);
            if (item != null) {
                item.interact("Scatter");
                return antiBan.varyDelay(randomDelay(BURY_DELAY_MIN_MS, BURY_DELAY_MAX_MS));
            }
        }
        return 800;
    }

    // ===================== SAFESPOT =====================

    private int handleSafespot() {
        if (safespotHandler == null) return handleFighting();

        IPlayer local = Players.getLocal();
        if (local == null) return 1000;

        boolean isInCombat = local.isInteracting();

        if (targetWasAlive && !isInCombat) {
            targetWasAlive = false;
            lootWindowEnd = System.currentTimeMillis() + POST_KILL_WAIT_MS;
            lastDeadNpcIndex = lastAttackedNpcIndex;
            lastAttackedNpcIndex = -1;
            String npcName = null;
            // Probeer de laatst bekende NPC via index te vinden; als dat niet lukt,
            // registreren we de kill zonder naam (valt terug op de generieke teller).
            if (lastDeadNpcIndex >= 0) {
                INPC lastNpc = (INPC) NPCs.getAll(lastDeadNpcIndex);
                if (lastNpc != null) npcName = lastNpc.getName();
            }
            if (npcName != null) {
                paint.addKill(npcName);
            } else {
                paint.addKill();
            }
            killsSinceLastLoot++;
            killsSinceArrowPickup++;
            safespotHandler.reset();
            return POST_KILL_WAIT_MS;
        }

        if (isInCombat) targetWasAlive = true;

        INPC target = findNewTarget();
        if (suppressNewAttacksForLogout && !isInCombat) {
            target = null;
        }
        int delay = safespotHandler.tick(target, isInCombat);

        if (target != null) {
            lastAttackedNpcIndex = System.identityHashCode(target);
        }

        switch (safespotHandler.getState()) {
            case ENGAGING:  paint.setCurrentStatus("⚔ Safespot: aanvallen"); break;
            case RETURNING: paint.setCurrentStatus("🛡 Safespot: terugkeren"); break;
            case WAITING:   paint.setCurrentStatus("🛡 Safespot: wachten"); break;
            case ATTACKING: paint.setCurrentStatus("⚔ Safespot: in combat"); break;
        }

        return antiBan.varyDelay(delay);
    }

    // ===================== FOOD HELPERS =====================

    private boolean hasFoodToEat() {
        if (isAnyFood()) return findAnyFood() != null;
        return Inventory.contains(config.foodName());
    }

    private boolean isAnyFood() {
        String name = config.foodName();
        return name == null || name.trim().equalsIgnoreCase("any");
    }

    private IInventoryItem findAnyFood() {
        return Inventory.getFirst(item ->
                item != null && item.getActions() != null &&
                        Arrays.stream(item.getActions()).anyMatch(a ->
                                a != null && (a.equalsIgnoreCase("Eat") || a.equalsIgnoreCase("Drink"))
                        )
        );
    }

    // ===================== RANGE HELPERS =====================

    private boolean isOutsideAttackRange() {
        IPlayer local = Players.getLocal();
        if (local == null || fightArea == null) return false;
        return local.getWorldLocation().distanceTo(fightArea) > centerRadius;
    }

    private boolean isAtLocation(WorldPoint target) {
        IPlayer local = Players.getLocal();
        if (local == null) return true;
        return local.getWorldLocation().distanceTo(target) <= LOCATION_THRESHOLD;
    }

    // ===================== HANDLERS =====================

    private int handleEating() {
        IInventoryItem food = isAnyFood() ? findAnyFood() : Inventory.getFirst(config.foodName());
        if (food != null) {
            food.interact("Eat");
            return antiBan.varyDelay(randomDelay(1200, 1800));
        }
        return 600;
    }

    /**
     * Berekent de attack cooldown op basis van config of standaard.
     * Als attackDelayMin/Max > 0 → gebruik die, anders standaard ATTACK_CLICK_COOLDOWN_MS.
     */
    private long getAttackCooldown() {
        int min = config.attackDelayMin();
        int max = config.attackDelayMax();
        if (min > 0 && max > 0) {
            if (max <= min) max = min + 200;
            return randomDelay(min, max);
        }
        return ATTACK_CLICK_COOLDOWN_MS;
    }

    /**
     * Ranged: quiver vullen / bank-route; voorkomt Attack zolang er geen werkende ammo is.
     * Ook elke tick vóór andere combat-gedrag (niet alleen bij nieuw doelwit).
     */
    private int enforceRangedAmmoOrDelay() {
        if (config.combatStyle() != CombatBotConfig.ImpsCombatStyle.RANGED
                || Equipment.contains("Toxic blowpipe")) {
            return 0;
        }
        int need = effectiveRangedAmmoBankMinimum();
        int total = getTotalRangedAmmoCount();
        if (shouldTryInventoryRangedAmmoEquip()) {
            if (tryEquipRangedAmmoFromInventory()) {
                return antiBan.varyDelay(randomDelay(450, 850));
            }
            if (inventoryHasUsableRangedAmmo()) {
                return antiBan.varyDelay(randomDelay(450, 850));
            }
            noAmmoDetected = true;
            paint.setLastAntiBanAction("🏹 Geen ammo in quiver — bank/GE");
            return antiBan.varyDelay(randomDelay(500, 900));
        }
        if (total < need) {
            noAmmoDetected = true;
            paint.setLastAntiBanAction("🏹 Te weinig pijlen (totaal) — bank/GE");
            return antiBan.varyDelay(randomDelay(400, 800));
        }
        return 0;
    }

    private int handleFighting() {
        IPlayer local = Players.getLocal();
        if (local == null) return 1000;

        int ammoDelay = enforceRangedAmmoOrDelay();
        if (ammoDelay > 0) {
            return ammoDelay;
        }

        // Al in combat — gewoon wachten
        if (local.isInteracting()) {
            targetWasAlive = true;
            lootWindowEnd = 0;
            return antiBan.varyDelay(randomDelay(600, 1200));
        }

        // Check of we worden aangevallen door een NPC → vecht terug (niet bij panel-uitloggen)
        if (isBeingAttacked() && !suppressNewAttacksForLogout) {
            List<INPC> attackers = NPCs.getAll(npc ->
                    npc != null && npc.isInteracting()
                            && npc.getInteracting() == local.getWrapped()
                            && !npc.isDead());
            if (attackers != null && !attackers.isEmpty()) {
                int retalAmmo = enforceRangedAmmoOrDelay();
                if (retalAmmo > 0) {
                    return retalAmmo;
                }
                INPC attacker = attackers.get(0);
                lastAttackedNpcIndex = System.identityHashCode(attacker);
                lastAttackClickTime = System.currentTimeMillis();
                attacker.interact("Attack");
                targetWasAlive = true;
                return antiBan.varyDelay(randomDelay(800, 1400));
            }
        }
        if (isBeingAttacked() && suppressNewAttacksForLogout) {
            return antiBan.varyDelay(randomDelay(600, 1200));
        }

        if (local.isMoving()) {
            return antiBan.varyDelay(randomDelay(400, 800));
        }

        if (targetWasAlive) {
            targetWasAlive = false;
            lootWindowEnd = System.currentTimeMillis() + POST_KILL_WAIT_MS;
            lastDeadNpcIndex = lastAttackedNpcIndex;
            lastAttackedNpcIndex = -1;
            paint.addKill();
            killsSinceLastLoot++;
            killsSinceArrowPickup++;
            return POST_KILL_WAIT_MS;
        }

        // Configurable attack delay
        long cooldown = getAttackCooldown();
        if (System.currentTimeMillis() - lastAttackClickTime < cooldown) {
            return antiBan.varyDelay(randomDelay(300, 600));
        }

        if (suppressNewAttacksForLogout) {
            return antiBan.varyDelay(randomDelay(400, 800));
        }

        INPC target = findNewTarget();
        if (target != null) {
            int newTarAmmo = enforceRangedAmmoOrDelay();
            if (newTarAmmo > 0) {
                return newTarAmmo;
            }
            lastAttackedNpcIndex = System.identityHashCode(target);
            lastAttackClickTime = System.currentTimeMillis();
            target.interact("Attack");
            return antiBan.varyDelay(randomDelay(800, 1400));
        }
        return antiBan.varyDelay(randomDelay(1000, 2000));
    }

    /**
     * Slimme NPC targeting: kiest het doelwit dat het snelst bereikbaar is.
     * Filtert NPCs die al in combat zijn met een andere speler (tenzij multi-combat zone).
     */
    private INPC findNewTarget() {
        List<String> targetNames = Arrays.stream(config.monsterName().split(","))
                .map(s -> s.trim().toLowerCase())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        IPlayer local = Players.getLocal();
        if (local == null) return null;

        boolean isMultiCombat = NpcCombatTargetHelper.isInMultiCombatZone();

        List<INPC> candidates = NPCs.getAll(npc ->
                npc.getName() != null
                        && targetNames.contains(npc.getName().toLowerCase())
                        && System.identityHashCode(npc) != lastDeadNpcIndex
                        && !isTileExcludedForNpc(npc.getWorldLocation(), getNpcSize(npc))
                        && isWithinArea(npc.getWorldLocation())
                        && !npc.isDead()
                        && (isMultiCombat || !NpcCombatTargetHelper.isNpcInCombatWithOther(npc, local))
        );

        if (candidates == null || candidates.isEmpty()) return null;

        INPC bestTarget = null;
        int bestDistance = Integer.MAX_VALUE;

        for (INPC npc : candidates) {
            if (!Reachable.isInteractable(npc)) continue;

            int pathDist;
            try {
                pathDist = Movement.calculateDistance(npc.getWorldLocation());
            } catch (Exception e) {
                pathDist = local.getWorldLocation().distanceTo(npc.getWorldLocation());
            }

            // Extra check: als pathDist extreem hoog is (achter muur), skip
            int tileDist = local.getWorldLocation().distanceTo(npc.getWorldLocation());
            if (pathDist > tileDist * 3 && pathDist > 15) continue;

            if (pathDist < bestDistance) {
                bestDistance = pathDist;
                bestTarget = npc;
            }
        }

        if (bestTarget == null) {
            return Combat.getAttackableNPC(npc ->
                    npc.getName() != null
                            && targetNames.contains(npc.getName().toLowerCase())
                            && System.identityHashCode(npc) != lastDeadNpcIndex
                            && !isTileExcludedForNpc(npc.getWorldLocation(), getNpcSize(npc))
                            && isWithinArea(npc.getWorldLocation())
                            && (isMultiCombat || !NpcCombatTargetHelper.isNpcInCombatWithOther(npc, local))
            );
        }

        return bestTarget;
    }

    /**
     * Check of de speler ook door een NPC wordt aangevallen (defensive check).
     * Gebruikt voor het prioriteren van zelfverdediging.
     */
    private boolean isBeingAttacked() {
        IPlayer local = Players.getLocal();
        if (local == null) return false;
        // Check of een NPC ons target
        List<INPC> attackers = NPCs.getAll(npc ->
                npc != null && npc.isInteracting()
                        && npc.getInteracting() == local.getWrapped()
                        && !npc.isDead());
        return attackers != null && !attackers.isEmpty();
    }

    /** Check of een NPC binnen het center-gebied is. Geen center = geen beperking. */
    private boolean isWithinArea(WorldPoint point) {
        if (fightArea == null || point == null) return true;
        return Math.abs(point.getX() - fightArea.getX()) <= centerRadius
                && Math.abs(point.getY() - fightArea.getY()) <= centerRadius;
    }

    private boolean isTileExcluded(WorldPoint point) {
        return isTileExcludedForNpc(point, 1);
    }

    /**
     * Check excluded tiles voor een NPC. Controleert alle tiles die de NPC inneemt
     * (size x size) plus 1 ring eromheen, zodat ook grote NPCs achter muren
     * correct worden gefilterd als je een aangrenzende tile markeert.
     */
    private boolean isTileExcludedForNpc(WorldPoint swCorner, int npcSize) {
        if (tileMarkerManager == null || swCorner == null) return false;
        // Check alle tiles die de NPC bezet + 1 ring eromheen
        for (int dx = -1; dx <= npcSize; dx++) {
            for (int dy = -1; dy <= npcSize; dy++) {
                WorldPoint check = new WorldPoint(swCorner.getX() + dx, swCorner.getY() + dy, swCorner.getPlane());
                if (tileMarkerManager.isTileExcluded(CombatBotPlugin.ActiveSkill.COMBAT, check)) return true;
            }
        }
        return false;
    }

    /**
     * Grond-loot staat op één WorldPoint: alleen die tile telt (geen ring zoals bij NPC-footprint).
     */
    private boolean isGroundLootTileExcluded(WorldPoint wp) {
        if (tileMarkerManager == null || wp == null) return false;
        return tileMarkerManager.isTileExcluded(CombatBotPlugin.ActiveSkill.COMBAT, wp);
    }

    /**
     * Haal NPC size op via de underlying NPCComposition.
     * Fallback naar 1 als niet beschikbaar.
     */
    private int getNpcSize(INPC npc) {
        try {
            if (npc.getComposition() != null) {
                return npc.getComposition().getSize();
            }
        } catch (Exception ignored) {}
        return 1;
    }

    private int handleLooting() {
        if (shouldAbortActions()) return 300;
        // Loot pickup cooldown — voorkom te snel klikken
        if (System.currentTimeMillis() - lastLootPickupTime < LOOT_PICKUP_COOLDOWN_MS) {
            return antiBan.varyDelay(randomDelay(400, 800));
        }

        // Check of speler nog bezig is met vorige pickup
        IPlayer local = Players.getLocal();
        if (local != null && (local.isMoving() || local.getAnimation() != -1)) {
            return antiBan.varyDelay(randomDelay(300, 600));
        }

        List<String> lootNames = getLootItemNames();

        ITileItem loot = null;

        if (!lootNames.isEmpty()) {
            if (config.lootOnlyOwn()) {
                loot = TileItems.getAllMine().stream()
                        .filter(item -> item != null && item.getWorldLocation() != null
                                && !isGroundLootTileExcluded(item.getWorldLocation()))
                        .filter(item -> item.getName() != null && lootNames.stream()
                                .anyMatch(n -> n.equalsIgnoreCase(item.getName())))
                        .findFirst().orElse(null);
            } else {
                loot = TileItems.getNearest(item -> item != null && item.getWorldLocation() != null
                        && !isGroundLootTileExcluded(item.getWorldLocation())
                        && item.getName() != null && lootNames.stream()
                        .anyMatch(n -> n.equalsIgnoreCase(item.getName())));
            }
        }

        if (loot == null && config.lootByMinValue() && config.lootMinValue() > 0) {
            int minVal = config.lootMinValue();
            if (config.lootOnlyOwn()) {
                loot = TileItems.getAllMine().stream()
                        .filter(item -> item != null && item.getWorldLocation() != null
                                && !isGroundLootTileExcluded(item.getWorldLocation()))
                        .filter(item -> item.getHaPrice() >= minVal)
                        .findFirst().orElse(null);
            } else {
                loot = TileItems.getNearest(item -> item != null && item.getWorldLocation() != null
                        && !isGroundLootTileExcluded(item.getWorldLocation())
                        && item.getHaPrice() >= minVal);
            }
        }

        if (loot != null && !Inventory.isFull()) {
            String name = loot.getName();
            int qty = loot.getQuantity();
            int ha = loot.getHaPrice() * qty;
            loot.pickup();
            lastLootPickupTime = System.currentTimeMillis();
            if (name != null) {
                paint.addLoot(name, qty, ha);
            } else {
                paint.addLoot(ha);
            }
            // Blijf in LOOTING state — reset pas als er geen loot meer is
            return antiBan.varyDelay(randomDelay(1200, 2000));
        }
        // Geen loot meer gevonden — reset kill counter zodat nieuwe cycle begint
        killsSinceLastLoot = 0;
        effectiveLootKills = -1;
        return 600;
    }

    private int handleWalkingToBank() {
        if (shouldAbortActions()) return 300;
        if (Bank.isOpen()) return 600;

        // Probeer dichtstbijzijnde bank te interacten als we erbij staan
        if (BankHelper.interactIfNearby()) {
            paint.setLastAntiBanAction("🏦 Bank openen");
            return antiBan.varyDelay(randomDelay(1500, 2500));
        }

        if (!BankHelper.walkToNearestFullBank()) {
            paint.setLastAntiBanAction("⚠ Geen bank gevonden!");
            return 5000;
        }
        paint.setLastAntiBanAction("→ Lopen naar bank");
        return antiBan.varyDelay(randomDelay(2000, 3000));
    }

    /** Build UBM requirements voor combat: food + ammo/runes. */
    private java.util.List<UniversalBankingManager.Requirement> buildCombatRequirements() {
        java.util.List<UniversalBankingManager.Requirement> reqs = new java.util.ArrayList<>();

        // Food
        String foodName = detectBestFood();
        if (foodName == null || foodName.isEmpty()) {
            String cfgFood = config.foodName();
            if (cfgFood != null && cfgFood.equalsIgnoreCase("any")) {
                CombatGeFoodType ge = config.combatGeFoodType();
                foodName = (ge == CombatGeFoodType.ANY)
                        ? CombatFoodPriority.firstTargetWhenAnyAndBankUnknown()
                        : ge.toItemName();
            } else {
                foodName = cfgFood;
            }
        }
        if (foodName != null && !foodName.isEmpty() && !foodName.equalsIgnoreCase("any")) {
            reqs.add(new UniversalBankingManager.Requirement(foodName, 1, config.foodAmount()));
        }

        // Combat style specifieke ammo/runes
        switch (config.combatStyle()) {
            case RANGED:
                if (!Equipment.contains("Toxic blowpipe")) {
                    String ammo = findBestAmmoInBank();
                    if (ammo == null || ammo.isEmpty()) {
                        String fallback = config.combatGeRangedAmmoItem();
                        ammo = (fallback != null && !fallback.trim().isEmpty())
                                ? fallback.trim()
                                : "Bronze arrow";
                    }
                    int target = Math.max(config.combatArrowTarget(), 100);
                    int minPull = effectiveRangedAmmoBankMinimum();
                    reqs.add(new UniversalBankingManager.Requirement(ammo, Math.min(minPull, target), target));
                }
                // Weapon als niet equipped
                if (!hasRangedWeaponEquipped()) {
                    String weapon = findWeaponInBank("bow", "shortbow", "longbow");
                    if (weapon != null) reqs.add(new UniversalBankingManager.Requirement(weapon, 1, 1));
                }
                break;
            case MAGE:
                String rune = findBestRuneInBank();
                if (rune != null) {
                    int target = Math.max(config.combatRuneTarget(), 50);
                    reqs.add(new UniversalBankingManager.Requirement(rune, 10, target));
                }
                if (!hasMagicWeaponEquipped()) {
                    String weapon = findWeaponInBank("staff", "wand");
                    if (weapon != null) reqs.add(new UniversalBankingManager.Requirement(weapon, 1, 1));
                }
                break;
            case MELEE:
                if (!hasMeleeWeaponEquipped()) {
                    String weapon = findWeaponInBank("scimitar", "sword", "dagger", "mace");
                    if (weapon != null) reqs.add(new UniversalBankingManager.Requirement(weapon, 1, 1));
                }
                break;
        }
        // Amulet of power (gear prep voor elke combat style als de speler het heeft)
        if (Bank.contains("Amulet of power")) {
            reqs.add(new UniversalBankingManager.Requirement("Amulet of power", 1, 1));
        }
        return reqs;
    }

    /** Zoek beste ammo in bank die de speler qua level kan gebruiken. */
    private String findBestAmmoInBank() {
        if (!Bank.isOpen()) return null;
        int rangedLevel = getSkillLevel(Skill.RANGED);
        for (Object[] entry : RangedAmmoKit.LEVELED_AMMO) {
            String name = (String) entry[0];
            int reqLevel = (int) entry[1];
            if (rangedLevel >= reqLevel && Bank.contains(name)) {
                debug("findBestAmmo: " + name + " (level " + rangedLevel + " >= " + reqLevel + ")");
                return name;
            }
        }
        return null;
    }

    /** Zoek beste rune in bank. */
    private String findBestRuneInBank() {
        if (!Bank.isOpen()) return null;
        for (String rune : ALL_RUNE_NAMES) {
            if (Bank.contains(rune)) return rune;
        }
        return null;
    }

    /** Zoek beste wapen in bank dat de speler qua level kan gebruiken. */
    private String findWeaponInBank(String... keywords) {
        if (!Bank.isOpen()) return null;
        // Bepaal welke leveled array en welk skill level we gebruiken
        Object[][] leveledList = null;
        Skill requiredSkill = Skill.ATTACK;
        for (String kw : keywords) {
            if (kw.contains("bow") || kw.contains("shortbow") || kw.contains("longbow")) {
                leveledList = LEVELED_RANGED_WEAPONS;
                requiredSkill = Skill.RANGED;
                break;
            } else if (kw.contains("staff") || kw.contains("wand")) {
                leveledList = LEVELED_MAGIC_WEAPONS;
                requiredSkill = Skill.MAGIC;
                break;
            } else if (kw.contains("scimitar") || kw.contains("sword") || kw.contains("dagger") || kw.contains("mace")) {
                leveledList = LEVELED_MELEE_WEAPONS;
                requiredSkill = Skill.ATTACK;
                break;
            }
        }

        if (leveledList != null) {
            int level = getSkillLevel(requiredSkill);
            for (Object[] entry : leveledList) {
                String name = (String) entry[0];
                int reqLevel = (int) entry[1];
                if (level >= reqLevel && Bank.contains(name)) {
                    debug("findWeapon: " + name + " (level " + level + " >= " + reqLevel + ")");
                    return name;
                }
            }
        }
        return null;
    }

    /** Veilige skill level ophalen. */
    private int getSkillLevel(Skill skill) {
        try {
            return Skills.getLevel(skill);
        } catch (Exception e) {
            return 1;
        }
    }

    /** Totaal equipped ranged ammo (quiver); zie {@link Equipment#getCount(boolean, java.util.function.Predicate)}. */
    private int getEquippedRangedAmmoQuantity() {
        return RangedAmmoKit.getEquippedRangedAmmoQuantity();
    }

    private int getInventoryRangedAmmoQuantity() {
        return RangedAmmoKit.getInventoryRangedAmmoQuantity(getSkillLevel(Skill.RANGED));
    }

    /** Quiver + rugzak (bolts/pijlen/etc.), voor bank-drempel en combat-checks. */
    private int getTotalRangedAmmoCount() {
        if (Equipment.contains("Toxic blowpipe")) {
            return Integer.MAX_VALUE;
        }
        return getEquippedRangedAmmoQuantity() + getInventoryRangedAmmoQuantity();
    }

    /** Minimaal aantal ranged projectielen voordat bank/GE; nooit &lt; 100. */
    private int effectiveRangedAmmoBankMinimum() {
        int c = config.combatArrowMin();
        if (c <= 0) {
            c = 100;
        }
        return Math.max(100, c);
    }

    private boolean isRangedAmmoItemName(String name) {
        return RangedAmmoKit.nameLooksLikeRangedAmmo(name);
    }

    private boolean recentRangedEmptyQuiverGameMessage() {
        return System.currentTimeMillis() - lastRangedEmptyQuiverGameMessageMs < 6000L;
    }

    /** Of we ammo uit de rugzak moeten (lege quiver óf game zegt net dat die leeg is). */
    private boolean shouldTryInventoryRangedAmmoEquip() {
        return getEquippedRangedAmmoQuantity() <= 0 || recentRangedEmptyQuiverGameMessage();
    }

    private IInventoryItem findBestRangedAmmoInInventory() {
        return RangedAmmoKit.findBestRangedAmmoInInventory(getSkillLevel(Skill.RANGED));
    }

    /** Zit er bruikbare ranged ammo in de rugzak (rekening houdend met Ranged level voor bekende types)? */
    private boolean inventoryHasUsableRangedAmmo() {
        return RangedAmmoKit.inventoryHasUsableRangedAmmo(getSkillLevel(Skill.RANGED));
    }

    private boolean shouldDeferAmmoBankForInventoryEquip() {
        if (config.combatStyle() != CombatBotConfig.ImpsCombatStyle.RANGED) {
            return false;
        }
        if (Equipment.contains("Toxic blowpipe")) {
            return false;
        }
        return inventoryHasUsableRangedAmmo();
    }

    /**
     * Lege quiver maar stacks in inventory: equip beste bruikbare ammo (volgens {@link RangedAmmoKit#LEVELED_AMMO}).
     * @return true als er ge-wield is of quiver al gevuld was
     */
    private boolean tryEquipRangedAmmoFromInventory() {
        if (config.combatStyle() != CombatBotConfig.ImpsCombatStyle.RANGED) {
            return false;
        }
        String equipped = RangedAmmoKit.tryEquipRangedAmmoFromInventoryReturningDisplayName(
                getSkillLevel(Skill.RANGED),
                lastRangedEmptyQuiverGameMessageMs,
                this::sleep,
                Equipment.contains("Toxic blowpipe"));
        if (equipped == null) {
            debug("tryEquipRangedAmmo: equip mislukt of geen stack");
            return false;
        }
        noAmmoDetected = false;
        if (!equipped.isEmpty()) {
            lastRangedEmptyQuiverGameMessageMs = 0L;
            chatLog("🏹 Ammo uit inventory: " + equipped);
        }
        return true;
    }

    private boolean tryGeRestockRangedAmmo(String itemName, int quantity) {
        if (shouldAbortActions()) return false;
        if (itemName == null || itemName.isEmpty() || quantity <= 0) return false;
        int basePrice = Math.max(1, config.combatGeRangedAmmoBasePrice());
        debug("[GE-Ammo][Combat] koop " + quantity + "× " + itemName + " @" + basePrice + " gp");
        GeRestockHelper.RestockResult r = GeRestockHelper.buyWithEscalation(
                itemName,
                quantity,
                basePrice,
                config::botEnabled
        );
        if (r == GeRestockHelper.RestockResult.SUCCESS) {
            return true;
        }
        int increased = (int) Math.round(basePrice * 1.2);
        r = GeRestockHelper.buyWithEscalation(
                itemName,
                quantity,
                increased,
                config::botEnabled
        );
        return r == GeRestockHelper.RestockResult.SUCCESS;
    }

    private boolean tryGeRestockFood(int shortage, String itemName) {
        if (shouldAbortActions()) return false;
        if (shortage <= 0 || itemName == null || itemName.isEmpty()) return false;
        int targetTotal = config.foodAmount();
        if (targetTotal <= 0) return false;

        int basePrice = Math.max(1, config.combatGeFoodBasePrice());

        debug("[GE-Food][Combat] start restock: totaal " + targetTotal + "x " + itemName + " (tekort was " + shortage + ") @" + basePrice);

        GeRestockHelper.RestockResult r = GeRestockHelper.buyWithEscalation(
                itemName,
                targetTotal,
                basePrice,
                config::botEnabled
        );
        if (r == GeRestockHelper.RestockResult.SUCCESS) {
            debug("GE food restock OK: totaal " + targetTotal + "x " + itemName + " @" + basePrice);
            return true;
        }

        int increased = (int) Math.round(basePrice * 1.2);
        debug("[GE-Food][Combat] eerste poging geen SUCCESS, retry met +20% → " + increased);
        r = GeRestockHelper.buyWithEscalation(
                itemName,
                targetTotal,
                increased,
                config::botEnabled
        );
        if (r == GeRestockHelper.RestockResult.SUCCESS) {
            debug("GE food restock OK (20% hoger): totaal " + targetTotal + "x " + itemName + " @" + increased);
            return true;
        }

        debug("GE food restock FAILED: " + itemName + " doel=" + targetTotal);
        return false;
    }

    /**
     * Bij {@link CombatGeFoodType#ANY}: begin bij het item dat de bank mist, daarna lagere tiers in {@link CombatFoodPriority#BEST_FIRST}.
     */
    private boolean tryGeRestockFoodAnyMode(int shortage, String missingFromBank) {
        if (shouldAbortActions()) return false;
        if (shortage <= 0) return false;
        int start = CombatFoodPriority.indexInBestFirst(missingFromBank);
        for (int i = start; i < CombatFoodPriority.BEST_FIRST.length && !shouldAbortActions(); i++) {
            if (tryGeRestockFood(shortage, CombatFoodPriority.BEST_FIRST[i])) {
                return true;
            }
        }
        return false;
    }

    /** Mag Combat GE-food dit tekort aanpakken? Eerste UBM-requirement is altijd food. */
    private boolean combatGeFoodAppliesToMissing(String missing, String foodRequirementName) {
        if (missing == null || missing.isEmpty()) return false;
        if (foodRequirementName == null || foodRequirementName.isEmpty()) return false;
        if (!missing.equalsIgnoreCase(foodRequirementName)) return false;
        CombatGeFoodType t = config.combatGeFoodType();
        if (t == CombatGeFoodType.ANY) {
            return true;
        }
        String want = t.toItemName();
        return want != null && missing.equalsIgnoreCase(want);
    }

    public boolean hasOtherActiveSkills() {
        return config.impsMode() || config.giantsMode() || config.barbLootEnabled()
                || (config.wcEnabled() && CenterManager.countActive(config.wcCenters()) > 0)
                || (config.miningEnabled() && CenterManager.countActive(config.miningCenters()) > 0)
                || (config.fishingEnabled() && CenterManager.countActive(config.fishingCenters()) > 0);
    }

    /** Plugin kan dit uitlezen om Combat uit rotatie te halen bij geen food (bank+GE). */
    public boolean isOutOfFoodNoGeRestock() {
        return outOfFoodNoGeRestock;
    }

    public void resetOutOfFoodNoGeRestock() {
        outOfFoodNoGeRestock = false;
    }

    /** Bank via UBM: deposit loot, withdraw food + gear. */
    private int handleBanking() {
        if (shouldAbortActions()) return 300;
        if (!Bank.isOpen()) {
            BankHelper.tryOpenFullBank();
            return antiBan.varyDelay(randomDelay(1200, 1800));
        }

        java.util.List<UniversalBankingManager.Requirement> reqs = buildCombatRequirements();
        String foodReqName = null;
        if (!reqs.isEmpty()) {
            foodReqName = reqs.get(0).getItemName();
        }
        UniversalBankingManager.BankSessionStatus status = bankingManager.runBankSession(reqs);
        if (shouldAbortActions()) return 300;

        debug("handleBanking: UBM result=" + status.getResult());

        noCenterGearFetched = true;
        noRunesDetected = false;
        noAmmoDetected = false;

        if (status.getResult() == UniversalBankingManager.BankSessionResult.NEEDS_GE_RESTOCK) {
            String missing = status.getRestockItemName();
            int shortage = status.getRestockShortage();
            debug("UBM NEEDS_GE_RESTOCK: " + missing + " shortage=" + shortage);

            boolean geOk = false;
            if (config.combatGeFoodEnabled() && combatGeFoodAppliesToMissing(missing, foodReqName)) {
                if (config.combatGeFoodType() == CombatGeFoodType.ANY) {
                    geOk = tryGeRestockFoodAnyMode(shortage, missing);
                } else {
                    geOk = tryGeRestockFood(shortage, missing);
                }
                if (geOk) {
                    Bank.close();
                    bankingManager.waitForBankClose();
                    BankHelper.tryOpenFullBank();
                    return antiBan.varyDelay(randomDelay(1200, 1800));
                }
            }

            if (config.combatGeRangedAmmoEnabled()
                    && config.combatStyle() == CombatBotConfig.ImpsCombatStyle.RANGED
                    && !Equipment.contains("Toxic blowpipe")
                    && isRangedAmmoItemName(missing)) {
                String buyName = config.combatGeRangedAmmoItem();
                if (buyName == null || buyName.trim().isEmpty()) {
                    buyName = "Bronze arrow";
                } else {
                    buyName = buyName.trim();
                }
                int buyQty = Math.max(shortage, Math.max(config.combatArrowTarget(), 100));
                geOk = tryGeRestockRangedAmmo(buyName, buyQty);
                if (geOk) {
                    Bank.close();
                    bankingManager.waitForBankClose();
                    BankHelper.tryOpenFullBank();
                    return antiBan.varyDelay(randomDelay(1200, 1800));
                }
            }

            // GE niet aan of gefaald → markeer dat Combat moet stoppen ivm geen food
            Bank.close();
            bankingManager.waitForBankClose();

            if (hasOtherActiveSkills()) {
                debug("Geen food (bank+GE) → Combat moet stoppen, rotatie gaat verder.");
                outOfFoodNoGeRestock = true;
                return antiBan.varyDelay(randomDelay(800, 1200));
            } else {
                debug("Geen food (bank+GE) en geen andere skills → bot in pauze, wacht op gebruiker.");
                outOfFoodNoGeRestock = true;
                return 60_000;
            }
        }

        // Kleine pauze voordat we bank sluiten (menselijk gedrag)
        sleep(300, 600);
        Bank.close();
        bankingManager.waitForBankClose();

        // Na banking: equip wapen, ammo (quiver), amulet als ze in inventory zitten
        equipWeaponFromInventory();
        tryEquipRangedAmmoFromInventory();
        equipAmuletOfPowerFromInventory();

        return antiBan.varyDelay(randomDelay(600, 1000));
    }

    /**
     * Equip een wapen uit inventory als we er geen hebben equipped.
     * Voorkomt infinite bank loops waar UBM het wapen vindt in inventory
     * maar hasAmmoForCombatStyle() Equipment checkt.
     */
    private void equipWeaponFromInventory() {
        try {
            switch (config.combatStyle()) {
                case MELEE:
                    if (!hasMeleeWeaponEquipped()) {
                        equipFirstMatch("scimitar", "sword", "dagger", "mace", "axe", "halberd", "spear", "whip", "claw");
                    }
                    break;
                case RANGED:
                    if (!hasRangedWeaponEquipped()) {
                        equipFirstMatch("shortbow", "longbow", "crossbow", "bow");
                    }
                    break;
                case MAGE:
                    if (!hasMagicWeaponEquipped()) {
                        equipFirstMatch("staff", "wand");
                    }
                    break;
            }
        } catch (Exception e) {
            debug("equipWeaponFromInventory fout: " + e.getMessage());
        }
    }

    /** Zoek eerste match in inventory en equip het. */
    private void equipFirstMatch(String... keywords) {
        IInventoryItem weapon = Inventory.getFirst(item -> {
            if (item == null || item.getName() == null) return false;
            String name = item.getName().toLowerCase();
            for (String kw : keywords) {
                if (name.contains(kw)) return true;
            }
            return false;
        });
        if (weapon != null) {
            debug("equipWeaponFromInventory: equipping " + weapon.getName());
            weapon.interact("Wield");
            sleep(600, 900);
        }
    }

    /** Equip Amulet of power uit inventory na gear prep (Combat/Giants/Imps). */
    private void equipAmuletOfPowerFromInventory() {
        if (Equipment.contains(item -> item != null && item.getName() != null && item.getName().equalsIgnoreCase("Amulet of power"))) return;
        IInventoryItem amulet = Inventory.getFirst(item -> item != null && item.getName() != null && item.getName().equalsIgnoreCase("Amulet of power"));
        if (amulet != null) {
            debug("equipAmuletOfPowerFromInventory: equipping Amulet of power");
            amulet.interact(amulet.hasAction("Wear") ? "Wear" : "Wield");
            sleep(500, 800);
        }
    }

    private String detectBestFood() {
        return CombatFoodPriority.findBestInBank();
    }

    /**
     * Terugkeren naar center — start al met actie zodra we BINNEN de radius zijn.
     * Wacht niet tot we op het exacte center punt staan.
     */
    private int handleReturnToCenter() {
        if (fightArea == null) return 1000;

        IPlayer local = Players.getLocal();
        if (local == null) return 1000;

        int dist = local.getWorldLocation().distanceTo(fightArea);

        if (dist <= centerRadius) {
            centerWalkTarget = null;
            if (!hasAmmoForCombatStyle()) {
                return Bank.isOpen() ? handleBanking() : handleWalkingToBank();
            }
            paint.setCurrentStatus("⚔ In center radius — vechten!");
            return handleFighting();
        }

        if (centerWalkTarget == null) {
            centerWalkTarget = MovementHelper.getRandomPointInRadius(fightArea, centerRadius);
        }
        MovementHelper.walkTo(centerWalkTarget);
        paint.setLastAntiBanAction("↩ Terug naar center");
        return antiBan.varyDelay(randomDelay(1800, 2800));
    }

    private int handleWalkingToFight() {
        if (fightArea != null) {
            if (centerWalkTarget == null) {
                centerWalkTarget = MovementHelper.getRandomPointInRadius(fightArea, centerRadius);
            }
            MovementHelper.walkTo(centerWalkTarget);
            paint.setLastAntiBanAction("→ Combat locatie");
            return antiBan.varyDelay(randomDelay(2000, 3000));
        }
        return 1000;
    }

    // ===================== STATE CHECKS =====================

    private boolean hasRangedWeaponEquipped() {
        var list = Equipment.getAll(item -> {
            if (item == null || item.getName() == null) return false;
            String name = item.getName().toLowerCase();
            for (String w : RANGED_WEAPONS) {
                if (name.contains(w)) return true;
            }
            return false;
        });
        return list != null && !list.isEmpty();
    }

    private boolean hasMagicWeaponEquipped() {
        var list = Equipment.getAll(item -> {
            if (item == null || item.getName() == null) return false;
            String name = item.getName().toLowerCase();
            for (String w : MAGIC_WEAPONS) {
                if (name.contains(w)) return true;
            }
            return false;
        });
        return list != null && !list.isEmpty();
    }

    private boolean hasMeleeWeaponEquipped() {
        var list = Equipment.getAll(item -> {
            if (item == null || item.getName() == null) return false;
            String name = item.getName().toLowerCase();
            return name.contains("scimitar") || name.contains("sword") || name.contains("mace")
                    || name.contains("axe") || name.contains("halberd") || name.contains("spear")
                    || name.contains("whip") || name.contains("dagger") || name.contains("claw");
        });
        return list != null && !list.isEmpty();
    }

    /**
     * Check of de speler ammo/runes heeft voor de gekozen combat style (config.combatStyle, niet Imps).
     */
    private boolean hasAmmoForCombatStyle() {
        switch (config.combatStyle()) {
            case MELEE:
                return hasMeleeWeaponEquipped();
            case RANGED: {
                if (!hasRangedWeaponEquipped()) return false;
                if (Equipment.contains("Toxic blowpipe")) return true;
                int need = effectiveRangedAmmoBankMinimum();
                int total = getTotalRangedAmmoCount();
                if (total < need) {
                    return false;
                }
                int eq = getEquippedRangedAmmoQuantity();
                if (eq > 0 && !recentRangedEmptyQuiverGameMessage()) {
                    return true;
                }
                if (!inventoryHasUsableRangedAmmo()) {
                    return false;
                }
                return true;
            }
            case MAGE: {
                if (!hasMagicWeaponEquipped()) return false;
                var poweredItems = Equipment.getAll(item -> {
                    if (item == null || item.getName() == null) return false;
                    String name = item.getName().toLowerCase();
                    return name.contains("trident") || name.contains("sanguinesti") || name.contains("tumeken");
                });
                if (poweredItems != null && !poweredItems.isEmpty()) return true;
                return Inventory.getFirst(item -> {
                    if (item == null || item.getName() == null) return false;
                    return item.getName().toLowerCase().contains("rune");
                }) != null;
            }
            default:
                return true;
        }
    }

    private boolean shouldEat() {
        try {
            return Combat.getHealthPercent() < config.eatPercent();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasLootNearby() {
        List<String> names = getLootItemNames();
        boolean hasNamedLoot = false;

        if (!names.isEmpty()) {
            if (config.lootOnlyOwn()) {
                hasNamedLoot = TileItems.getAllMine().stream().anyMatch(item ->
                        item != null && item.getWorldLocation() != null
                                && !isGroundLootTileExcluded(item.getWorldLocation())
                                && item.getName() != null && names.stream().anyMatch(n -> n.equalsIgnoreCase(item.getName())));
            } else {
                hasNamedLoot = TileItems.getNearest(item ->
                        item != null && item.getWorldLocation() != null
                                && !isGroundLootTileExcluded(item.getWorldLocation())
                                && item.getName() != null && names.stream().anyMatch(n -> n.equalsIgnoreCase(item.getName()))) != null;
            }
        }

        if (hasNamedLoot) return true;

        if (config.lootByMinValue() && config.lootMinValue() > 0) {
            int minVal = config.lootMinValue();
            if (config.lootOnlyOwn()) {
                return TileItems.getAllMine().stream().anyMatch(item ->
                        item != null && item.getWorldLocation() != null
                                && !isGroundLootTileExcluded(item.getWorldLocation())
                                && item.getHaPrice() >= minVal);
            } else {
                return TileItems.getNearest(item ->
                        item != null && item.getWorldLocation() != null
                                && !isGroundLootTileExcluded(item.getWorldLocation())
                                && item.getHaPrice() >= minVal) != null;
            }
        }

        return false;
    }

    private boolean hasSpecialLootNearby() {
        List<String> specials = getSpecialLootNames();
        if (specials.isEmpty()) return false;
        if (config.lootOnlyOwn()) {
            return TileItems.getAllMine().stream().anyMatch(item ->
                    item != null && item.getWorldLocation() != null
                            && !isGroundLootTileExcluded(item.getWorldLocation())
                            && item.getName() != null && specials.stream().anyMatch(n -> n.equalsIgnoreCase(item.getName())));
        }
        return TileItems.getNearest(item ->
                item != null && item.getWorldLocation() != null
                        && !isGroundLootTileExcluded(item.getWorldLocation())
                        && item.getName() != null && specials.stream().anyMatch(n -> n.equalsIgnoreCase(item.getName()))) != null;
    }

    // ===================== HELPERS =====================

    private List<String> getLootItemNames() {
        List<String> result = new ArrayList<>();
        for (String entry : config.lootItems().split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.equalsIgnoreCase("rune") || trimmed.equalsIgnoreCase("runes")) {
                for (String rune : ALL_RUNE_NAMES) {
                    if (!result.contains(rune)) result.add(rune);
                }
            } else {
                if (!result.contains(trimmed)) result.add(trimmed);
            }
        }
        if (config.lootBonesAndAshes()) {
            for (String bone : BONE_NAMES) {
                if (!result.contains(bone)) result.add(bone);
            }
            if (!result.contains("Fiendish ashes")) result.add("Fiendish ashes");
        }
        return result;
    }

    private List<String> getSpecialLootNames() {
        List<String> result = new ArrayList<>();
        for (String entry : config.specialLootItems().split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.equalsIgnoreCase("rune") || trimmed.equalsIgnoreCase("runes")) {
                for (String rune : ALL_RUNE_NAMES) {
                    if (!result.contains(rune)) result.add(rune);
                }
            } else {
                if (!result.contains(trimmed)) result.add(trimmed);
            }
        }
        return result;
    }

    // ===================== ARROW PICKUP =====================

    private boolean isInCombat() {
        IPlayer local = Players.getLocal();
        return local != null && local.isInteracting();
    }

    private boolean shouldPickupArrows() {
        if (killsSinceArrowPickup < nextArrowPickupKills) return false;
        // Check of er arrows/bolts op de grond liggen
        return hasArrowsOnGround();
    }

    private boolean isRangedAmmoGroundItem(ITileItem item) {
        if (item == null || item.getName() == null || item.getWorldLocation() == null) return false;
        if (isGroundLootTileExcluded(item.getWorldLocation())) return false;
        String name = item.getName().toLowerCase();
        for (String a : RANGED_AMMO) {
            if (name.contains(a)) return true;
        }
        return false;
    }

    private ITileItem nearestRangedAmmoGroundItem() {
        if (config.lootOnlyOwn()) {
            IPlayer local = Players.getLocal();
            WorldPoint me = local != null ? local.getWorldLocation() : null;
            Predicate<ITileItem> pred = this::isRangedAmmoGroundItem;
            return TileItems.getAllMine().stream()
                    .filter(pred)
                    .min(me != null
                            ? Comparator.comparingInt(a -> a.getWorldLocation().distanceTo(me))
                            : Comparator.comparingInt(a -> 0))
                    .orElse(null);
        }
        return TileItems.getNearest(this::isRangedAmmoGroundItem);
    }

    private boolean hasArrowsOnGround() {
        return nearestRangedAmmoGroundItem() != null;
    }

    private int handlePickupArrows() {
        // Voorkom oppakken tijdens combat
        if (isInCombat()) return antiBan.varyDelay(randomDelay(600, 1200));

        // Pickup cooldown
        if (System.currentTimeMillis() - lastLootPickupTime < LOOT_PICKUP_COOLDOWN_MS) {
            return antiBan.varyDelay(randomDelay(400, 800));
        }

        IPlayer local = Players.getLocal();
        if (local != null && (local.isMoving() || local.getAnimation() != -1)) {
            return antiBan.varyDelay(randomDelay(300, 600));
        }

        ITileItem arrow = nearestRangedAmmoGroundItem();

        if (arrow != null && !Inventory.isFull()) {
            arrow.pickup();
            lastLootPickupTime = System.currentTimeMillis();
            chatLog("🏹 Arrow opgepakt: " + arrow.getName() + " x" + arrow.getQuantity());
            return antiBan.varyDelay(randomDelay(1200, 2000));
        }

        // Geen arrows meer op grond — reset counter
        killsSinceArrowPickup = 0;
        nextArrowPickupKills = randomDelay(config.pickupArrowsMinKills(),
                Math.max(config.pickupArrowsMinKills() + 1, config.pickupArrowsMaxKills()));
        return 600;
    }

    private int randomDelay(int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + random.nextInt(max - min);
    }

    private void sleep(int min, int max) {
        if (shouldAbortActions()) return;
        try { Thread.sleep(randomDelay(min, max)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private boolean shouldAbortActions() {
        return config == null || !config.botEnabled();
    }
}
