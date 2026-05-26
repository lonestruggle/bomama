package com.combatbot;

import net.runelite.api.coords.WorldPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.Skill;
import net.storm.api.movement.IMovement;
import net.storm.api.movement.TilePath;
import net.storm.api.movement.WalkOptions;
import net.storm.api.domain.actors.INPC;
import net.storm.api.domain.actors.IPlayer;
import net.storm.api.domain.items.IInventoryItem;
import net.storm.api.domain.tiles.ITileItem;
import net.storm.sdk.entities.NPCs;
import net.storm.sdk.entities.Players;
import net.storm.sdk.entities.TileItems;
import net.storm.sdk.entities.TileObjects;
import net.storm.sdk.game.Combat;
import net.storm.sdk.game.Skills;
import net.storm.sdk.items.Bank;
import net.storm.sdk.items.Equipment;
import net.storm.sdk.items.Inventory;
import net.storm.sdk.movement.Reachable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Barbarian mode (Varrock long hall):
 * - Attack barbarians in configured longhall area.
 * - Pickup cooked meat from table to refill food.
 * - Bank in Edgeville bank.
 */
public class BarbarianHandler {
    private final CombatBotConfig config;
    private final AntiBan antiBan;
    private final CombatBotPaint paint;
    private final Random random = new Random();

    private static final WorldPoint EDGEVILLE_BANK = new WorldPoint(3094, 3491, 0);
    private static final WorldPoint LONGHALL_EXIT_ANCHOR = new WorldPoint(3087, 3459, 0);
    private static final int EDGEVILLE_BANK_INTERACT_RADIUS = 8;
    private static final int LONGHALL_DOOR_CLOSED_ID = 11620;
    private static final int LONGHALL_DOOR_OPEN_ID = 11624;
    /** Barbarian bank-run: expliciet GEEN teleports gebruiken. */
    private static final WalkOptions GROUND_WALK_OPTIONS = WalkOptions.builder()
            .useTransports(true)
            .useTeleports(false)
            .build();
    private static final String COOKED_MEAT = "Cooked meat";
    private static final long ATTACK_COOLDOWN_MS = 1700L;
    private static final int MIN_FREE_SLOTS_FOR_LOOT = 5;
    private static final String[] BARBARIAN_LOOT_NAMES = {
            "Bronze arrow", "Iron arrow", "Chaos rune", "Earth rune", "Mind rune", "Fire rune", "Law rune",
            "Coins", "Cooked meat"
    };
    private static final String[] MELEE_WEAPON_ORDER = {
            "Dragon scimitar", "Rune scimitar", "Adamant scimitar", "Mithril scimitar", "Black scimitar", "Steel scimitar", "Iron scimitar", "Bronze scimitar",
            "Dragon longsword", "Rune longsword", "Adamant longsword", "Mithril longsword", "Black longsword", "Steel longsword", "Iron longsword", "Bronze longsword",
            "Dragon sword", "Rune sword", "Adamant sword", "Mithril sword", "Black sword", "Steel sword", "Iron sword", "Bronze sword",
            "Dragon dagger", "Rune dagger", "Adamant dagger", "Mithril dagger", "Black dagger", "Steel dagger", "Iron dagger", "Bronze dagger",
            "Rune mace", "Adamant mace", "Mithril mace", "Black mace", "Steel mace", "Iron mace", "Bronze mace"
    };
    private static final String[][] MELEE_GEAR_GROUPS = {
            {"Rune full helm", "Rune med helm", "Adamant full helm", "Adamant med helm", "Mithril full helm", "Mithril med helm", "Black full helm", "Black med helm", "Steel full helm", "Steel med helm", "Iron full helm", "Iron med helm", "Bronze full helm", "Bronze med helm"},
            {"Rune platebody", "Adamant platebody", "Mithril platebody", "Black platebody", "Steel platebody", "Iron platebody", "Bronze platebody", "Rune chainbody", "Adamant chainbody", "Mithril chainbody", "Black chainbody", "Steel chainbody", "Iron chainbody", "Bronze chainbody"},
            {"Rune platelegs", "Adamant platelegs", "Mithril platelegs", "Black platelegs", "Steel platelegs", "Iron platelegs", "Bronze platelegs", "Rune plateskirt", "Adamant plateskirt", "Mithril plateskirt", "Black plateskirt", "Steel plateskirt", "Iron plateskirt", "Bronze plateskirt"},
            {"Rune kiteshield", "Adamant kiteshield", "Mithril kiteshield", "Black kiteshield", "Steel kiteshield", "Iron kiteshield", "Bronze kiteshield", "Wooden shield"},
            {"Rune boots", "Adamant boots", "Mithril boots", "Black boots", "Steel boots", "Iron boots", "Bronze boots"},
            {"Rune gloves", "Adamant gloves", "Mithril gloves", "Black gloves", "Steel gloves", "Iron gloves", "Bronze gloves", "Leather gloves"}
    };
    private long lastAttackMs = 0L;
    private int nextMeatEatHpThreshold = 70;
    /** Bones pas begraven als random batch-drempel gehaald is (1..3), tenzij inventory-space krap is. */
    private int nextBuryThreshold = 1;
    /** Pickup van tafel-meat wordt bewust niet instant gedaan; random cooldown tussen attempts. */
    private long nextTableMeatAttemptAtMs = 0L;
    /** Anti-spam: na meat-click eerst even laten lopen i.p.v. opnieuw klikken. */
    private long nextMeatReclickAllowedAtMs = 0L;
    /** Voor vloeiend door-klikken tijdens bank-runs. */
    private long lastTravelClickMs = 0L;
    /** Stuck-detectie voor bankrun vanuit longhall. */
    private WorldPoint lastBankWalkPos = null;
    private int bankWalkStallTicks = 0;

    private enum State {
        EATING, BURYING, LOOTING, PICKUP_MEAT, WALK_TO_BANK, BANKING, WALK_TO_HALL, FIGHTING
    }

    public BarbarianHandler(CombatBotConfig config, AntiBan antiBan, CombatBotPaint paint) {
        this.config = config;
        this.antiBan = antiBan;
        this.paint = paint;
    }

    public void resetState() {
        lastAttackMs = 0L;
        nextMeatEatHpThreshold = randomMeatEatThreshold();
        nextBuryThreshold = randomBuryThreshold();
        nextTableMeatAttemptAtMs = System.currentTimeMillis() + randomTableMeatCooldownMs();
        nextMeatReclickAllowedAtMs = 0L;
        lastTravelClickMs = 0L;
        lastBankWalkPos = null;
        bankWalkStallTicks = 0;
    }

    public int loop() {
        if (shouldAbortActions()) return 300;
        IPlayer local = Players.getLocal();
        if (local == null || local.getWorldLocation() == null) return 800;

        State s = determineState(local);
        switch (s) {
            case EATING:
                paint.setCurrentStatus("Barbarian: eten");
                return handleEat();
            case BURYING:
                paint.setCurrentStatus("Barbarian: bones begraven");
                return handleBuryBones();
            case LOOTING:
                paint.setCurrentStatus("Barbarian: loot");
                return handleLoot();
            case PICKUP_MEAT:
                paint.setCurrentStatus("Barbarian: cooked meat pakken");
                return handlePickupMeat(local);
            case WALK_TO_BANK:
                paint.setCurrentStatus("Barbarian: naar Edge bank");
                return handleWalkToBank();
            case BANKING:
                paint.setCurrentStatus("Barbarian: banken");
                return handleBanking();
            case WALK_TO_HALL:
                paint.setCurrentStatus("Barbarian: naar longhall");
                return handleWalkToHall(local);
            case FIGHTING:
            default:
                paint.setCurrentStatus("Barbarian: vechten");
                return handleFighting(local);
        }
    }

    private State determineState(IPlayer local) {
        // Als loot-space laag is: eerst bones begraven om ruimte te maken zonder food te verspillen.
        if (Inventory.getFreeSlots() <= MIN_FREE_SLOTS_FOR_LOOT && shouldBuryBonesNow()) return State.BURYING;
        if (shouldEatRegular() && hasFoodToEat()) return State.EATING;
        if (shouldEatCookedMeatForSpace()) return State.EATING;
        if (shouldBuryBonesNow()) return State.BURYING;
        if (shouldLoot()) return State.LOOTING;
        if (shouldPickupCookedMeat(local)) return State.PICKUP_MEAT;
        if (shouldBank()) return Bank.isOpen() ? State.BANKING : State.WALK_TO_BANK;
        if (!isInHall(local.getWorldLocation())) return State.WALK_TO_HALL;
        return State.FIGHTING;
    }

    private boolean shouldBank() {
        int free = Inventory.getFreeSlots();
        if (free <= MIN_FREE_SLOTS_FOR_LOOT && !shouldEatCookedMeatForSpace()) return true;
        if (Inventory.isFull()) return true;
        int food = foodCount();
        return food <= Math.max(0, config.barbarianFoodMin());
    }

    private boolean shouldPickupCookedMeat(IPlayer local) {
        if (!config.barbarianPickupCookedMeat()) return false;
        if (!isInHall(local.getWorldLocation())) return false;
        if (Inventory.getFreeSlots() <= MIN_FREE_SLOTS_FOR_LOOT) return false;
        if (foodCount() >= Math.max(config.barbarianFoodMin(), config.barbarianFoodTarget())) return false;
        if (System.currentTimeMillis() < nextTableMeatAttemptAtMs) return false;
        if (System.currentTimeMillis() < nextMeatReclickAllowedAtMs) return false;
        ITileItem meat = findReachableTableMeat(local);
        return meat != null && !Inventory.isFull();
    }

    private int handlePickupMeat(IPlayer local) {
        nextTableMeatAttemptAtMs = System.currentTimeMillis() + randomTableMeatCooldownMs();
        // Niet elke spawn meteen pakken: soms bewust skippen voor menselijk patroon.
        if (random.nextInt(100) < randomTableMeatSkipChancePercent()) {
            return antiBan.varyDelay(randomDelay(350, 1200));
        }
        ITileItem meat = findReachableTableMeat(local);
        if (meat != null && !Inventory.isFull()) {
            meat.pickup();
            nextMeatReclickAllowedAtMs = System.currentTimeMillis() + randomDelay(2200, 4200);
            return antiBan.varyDelay(randomDelay(700, 1800));
        }
        if (!isInHall(local.getWorldLocation())) {
            return handleWalkToHall(local);
        }
        return antiBan.varyDelay(randomDelay(450, 1300));
    }

    private ITileItem findReachableTableMeat(IPlayer local) {
        if (local == null || local.getWorldLocation() == null) return null;
        return TileItems.getNearest(i ->
                i != null && i.getName() != null && COOKED_MEAT.equalsIgnoreCase(i.getName())
                        && i.getWorldLocation() != null
                        && isInHall(i.getWorldLocation())
                        && i.getWorldLocation().distanceTo(local.getWorldLocation()) <= 6
                        && Reachable.isInteractable(i));
    }

    private int handleWalkToBank() {
        IPlayer local = Players.getLocal();
        if (local != null && local.getWorldLocation() != null) {
            WorldPoint pos = local.getWorldLocation();
            if (lastBankWalkPos != null && lastBankWalkPos.equals(pos)) {
                bankWalkStallTicks++;
            } else {
                bankWalkStallTicks = 0;
                lastBankWalkPos = pos;
            }

            // Longhall-exit protocol:
            // 1) Deur dicht? open eerst.
            // 2) Deur open? klik een random tile buiten de hal richting Edge bank.
            if (isInHall(pos)) {
                if (isLonghallDoorClosedNearby(pos)) {
                    if (tryOpenLonghallDoorForExit(local)) {
                        return antiBan.varyDelay(randomDelay(500, 900));
                    }
                } else if (isLonghallDoorOpenNearby(pos)) {
                    WorldPoint out = randomPointAround(LONGHALL_EXIT_ANCHOR, 8);
                    walkGroundOnly(out);
                    lastTravelClickMs = System.currentTimeMillis();
                    return antiBan.varyDelay(TravelWalkHelper.postClickDelayMs(config, random));
                }

                // Als we toch vastlopen in de hal: één extra deur-open fallback.
                if (bankWalkStallTicks >= 3 && tryOpenLonghallDoorForExit(local)) {
                    bankWalkStallTicks = 0;
                    return antiBan.varyDelay(randomDelay(500, 900));
                }
            }
        }

        if (Bank.isOpen()) return 600;
        if (!TravelWalkHelper.reclickReady(lastTravelClickMs, config)) {
            return antiBan.varyDelay(TravelWalkHelper.shortWaitMs(random));
        }
        if (local != null && local.getWorldLocation() != null
                && local.getWorldLocation().distanceTo(EDGEVILLE_BANK) <= EDGEVILLE_BANK_INTERACT_RADIUS) {
            if (BankHelper.interactIfNearby()) {
                lastTravelClickMs = System.currentTimeMillis();
                return antiBan.varyDelay(TravelWalkHelper.postClickDelayMs(config, random));
            }
        }
        walkGroundOnly(EDGEVILLE_BANK);
        lastTravelClickMs = System.currentTimeMillis();
        return antiBan.varyDelay(TravelWalkHelper.postClickDelayMs(config, random));
    }

    private int handleBanking() {
        if (shouldAbortActions()) return 300;
        if (!Bank.isOpen()) return handleWalkToBank();

        List<String> keep = new ArrayList<>();
        keep.add("Coins");
        for (IInventoryItem i : Inventory.getAll()) {
            if (i == null || i.getName() == null) continue;
            String n = i.getName().toLowerCase();
            if (i.hasAction("Eat") || i.hasAction("Drink")
                    || n.contains("scimitar") || n.contains("sword") || n.contains("dagger")
                    || n.contains("mace") || n.contains("axe") || n.contains("shield")
                    || n.contains("helm") || n.contains("body") || n.contains("legs")
                    || n.contains("boots") || n.contains("gloves") || n.contains("cape")
                    || n.contains("amulet")) {
                if (!keep.contains(i.getName())) keep.add(i.getName());
            }
        }
        Bank.depositAllExcept(keep.toArray(new String[0]));
        sleep(180, 320);
        withdrawMultipleMeleeUpgradesFromBank(8);

        int target = Math.max(1, config.barbarianFoodTarget());
        int have = foodCount();
        if (have < target) {
            String choice = config.foodChoice().toItemName();
            String bankFood = choice;
            if (choice == null || choice.equalsIgnoreCase("any")) {
                bankFood = CombatFoodPriority.findBestInBank();
            }
            if (bankFood != null && Bank.contains(bankFood)) {
                Bank.withdraw(bankFood, target - have);
                sleep(220, 420);
            }
        }

        Bank.close();
        sleep(220, 420);
        wearBestMeleeFromInventory();
        return antiBan.varyDelay(randomDelay(420, 780));
    }

    private int handleWalkToHall(IPlayer local) {
        if (isInHall(local.getWorldLocation())) return 500;
        if (tryOpenLonghallDoor(local)) {
            return antiBan.varyDelay(randomDelay(700, 1200));
        }
        MovementHelper.walkToArea(getHallCenter(), getHallRadius());
        return antiBan.varyDelay(randomDelay(900, 1500));
    }

    private int handleFighting(IPlayer local) {
        if (tryOpenLonghallDoor(local)) {
            return antiBan.varyDelay(randomDelay(700, 1200));
        }
        if (local.isInteracting()) return antiBan.varyDelay(randomDelay(500, 900));
        if (System.currentTimeMillis() - lastAttackMs < ATTACK_COOLDOWN_MS) {
            return antiBan.varyDelay(randomDelay(350, 700));
        }
        INPC target = NPCs.getNearest(npc ->
                npc != null && npc.getName() != null
                        && npc.getName().toLowerCase().contains("barbarian")
                        && npc.hasAction("Attack")
                        && !npc.isDead()
                        && npc.getWorldLocation() != null
                        && npc.getWorldLocation().distanceTo(getHallCenter()) <= getHallRadius()
                        && (!npc.isInteracting() || (npc.getInteracting() != null && npc.getInteracting().equals(local))));
        if (target != null) {
            target.interact("Attack");
            lastAttackMs = System.currentTimeMillis();
            return antiBan.varyDelay(randomDelay(700, 1200));
        }
        return antiBan.varyDelay(randomDelay(600, 1000));
    }

    private boolean shouldLoot() {
        ITileItem loot = TileItems.getNearest(i -> i != null && i.getName() != null
                && i.getWorldLocation() != null && isInHall(i.getWorldLocation())
                && isWantedLootName(i.getName()));
        return loot != null && Inventory.getFreeSlots() > MIN_FREE_SLOTS_FOR_LOOT;
    }

    private int handleLoot() {
        ITileItem loot = TileItems.getNearest(i -> i != null && i.getName() != null
                && i.getWorldLocation() != null && isInHall(i.getWorldLocation())
                && isWantedLootName(i.getName()));
        if (loot != null && Inventory.getFreeSlots() > MIN_FREE_SLOTS_FOR_LOOT) {
            loot.pickup();
            return antiBan.varyDelay(randomDelay(900, 1400));
        }
        return antiBan.varyDelay(randomDelay(500, 800));
    }

    private boolean shouldBuryBonesNow() {
        if (!config.barbarianLootBones()) return false;
        int bones = boneCount();
        if (bones <= 0) {
            nextBuryThreshold = randomBuryThreshold();
            return false;
        }
        if (Inventory.getFreeSlots() <= MIN_FREE_SLOTS_FOR_LOOT) {
            return true;
        }
        return bones >= nextBuryThreshold;
    }

    private int handleBuryBones() {
        IInventoryItem bones = Inventory.getFirst(i ->
                i != null && i.getName() != null
                        && i.getName().toLowerCase().contains("bones")
                        && i.hasAction("Bury"));
        if (bones != null) {
            InventoryActionHelper.interact(config, bones, "Bury");
            if (boneCount() <= 0) {
                nextBuryThreshold = randomBuryThreshold();
            }
            return antiBan.varyDelay(randomDelay(900, 1500));
        }
        nextBuryThreshold = randomBuryThreshold();
        return antiBan.varyDelay(randomDelay(500, 900));
    }

    private int handleEat() {
        IInventoryItem food = null;
        if (shouldEatCookedMeatForSpace()) {
            food = Inventory.getFirst(i -> i != null && i.getName() != null && COOKED_MEAT.equalsIgnoreCase(i.getName()));
            // Na meat-eat direct nieuwe random threshold kiezen zodat het niet bot-matig vast patroon wordt.
            nextMeatEatHpThreshold = randomMeatEatThreshold();
        }
        if (food == null) {
            food = Inventory.getFirst(i ->
                    i != null && i.getActions() != null &&
                            java.util.Arrays.stream(i.getActions()).anyMatch(a ->
                                    a != null && (a.equalsIgnoreCase("Eat") || a.equalsIgnoreCase("Drink"))));
        }
        if (food != null) {
            InventoryActionHelper.interact(config, food, food.hasAction("Eat") ? "Eat" : "Drink");
            return antiBan.varyDelay(randomDelay(1000, 1500));
        }
        return 600;
    }

    private boolean shouldEatRegular() {
        try {
            // Barbarian mode: normale food pas bij 50% HP.
            return Combat.getHealthPercent() <= 50.0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean shouldEatCookedMeatForSpace() {
        if (Inventory.getFreeSlots() > MIN_FREE_SLOTS_FOR_LOOT) return false;
        IInventoryItem meat = Inventory.getFirst(i -> i != null && i.getName() != null && COOKED_MEAT.equalsIgnoreCase(i.getName()));
        if (meat == null) return false;
        try {
            double hp = Combat.getHealthPercent();
            if (hp >= 99.9) return false; // alleen als HP niet vol is
            return hp <= nextMeatEatHpThreshold;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasFoodToEat() {
        return foodCount() > 0;
    }

    private int foodCount() {
        var all = Inventory.getAll(i -> i != null && (i.hasAction("Eat") || i.hasAction("Drink")));
        return all != null ? all.size() : 0;
    }

    private boolean isInHall(WorldPoint p) {
        if (p == null) return false;
        int minX = Math.min(Math.min(config.barbarianCorner1X(), config.barbarianCorner2X()),
                Math.min(config.barbarianCorner3X(), config.barbarianCorner4X()));
        int maxX = Math.max(Math.max(config.barbarianCorner1X(), config.barbarianCorner2X()),
                Math.max(config.barbarianCorner3X(), config.barbarianCorner4X()));
        int minY = Math.min(Math.min(config.barbarianCorner1Y(), config.barbarianCorner2Y()),
                Math.min(config.barbarianCorner3Y(), config.barbarianCorner4Y()));
        int maxY = Math.max(Math.max(config.barbarianCorner1Y(), config.barbarianCorner2Y()),
                Math.max(config.barbarianCorner3Y(), config.barbarianCorner4Y()));
        return p.getPlane() == 0 && p.getX() >= minX && p.getX() <= maxX && p.getY() >= minY && p.getY() <= maxY;
    }

    private WorldPoint getHallCenter() {
        return new WorldPoint(config.barbarianHallX(), config.barbarianHallY(), 0);
    }

    private int getHallRadius() {
        return Math.max(3, config.barbarianHallRadius());
    }

    private boolean tryOpenLonghallDoor(IPlayer local) {
        if (local == null || local.getWorldLocation() == null) return false;
        if (isInHall(local.getWorldLocation())) return false;
        var door = TileObjects.getNearest(o -> o != null && o.getName() != null
                && o.getName().toLowerCase().contains("longhall door")
                && o.hasAction("Open")
                && o.getWorldLocation() != null
                && o.getWorldLocation().distanceTo(local.getWorldLocation()) <= 4);
        if (door != null) {
            door.interact("Open");
            return true;
        }
        return false;
    }

    private boolean tryOpenLonghallDoorForExit(IPlayer local) {
        if (local == null || local.getWorldLocation() == null) return false;
        var door = TileObjects.getNearest(o -> o != null && o.getName() != null
                && o.getName().toLowerCase().contains("longhall door")
                && o.hasAction("Open")
                && o.getWorldLocation() != null
                && o.getWorldLocation().distanceTo(local.getWorldLocation()) <= 5);
        if (door != null) {
            door.interact("Open");
            return true;
        }
        return false;
    }

    private boolean isLonghallDoorClosedNearby(WorldPoint pos) {
        var door = TileObjects.getNearest(o -> o != null
                && o.getId() == LONGHALL_DOOR_CLOSED_ID
                && o.getWorldLocation() != null
                && o.getWorldLocation().distanceTo(pos) <= 6);
        return door != null;
    }

    private boolean isLonghallDoorOpenNearby(WorldPoint pos) {
        var door = TileObjects.getNearest(o -> o != null
                && o.getId() == LONGHALL_DOOR_OPEN_ID
                && o.getWorldLocation() != null
                && o.getWorldLocation().distanceTo(pos) <= 6);
        return door != null;
    }

    private WorldPoint randomPointAround(WorldPoint anchor, int radius) {
        int r = Math.max(1, radius);
        int dx = random.nextInt(r * 2 + 1) - r;
        int dy = random.nextInt(r * 2 + 1) - r;
        return new WorldPoint(anchor.getX() + dx, anchor.getY() + dy, anchor.getPlane());
    }

    private boolean walkGroundOnly(WorldPoint destination) {
        if (destination == null) return false;
        try {
            IMovement movement = net.storm.api.Static.getMovement();
            if (movement != null) {
                WorldArea area = new WorldArea(destination.getX(), destination.getY(), 1, 1, destination.getPlane());
                if (movement.walkTo(area, GROUND_WALK_OPTIONS)) {
                    return true;
                }
                TilePath path = movement.getPath(destination);
                if (path != null && !path.isEmpty()) {
                    path.walk(GROUND_WALK_OPTIONS);
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private int randomDelay(int min, int max) {
        if (max <= min) return min;
        return min + random.nextInt(max - min);
    }

    private int randomMeatEatThreshold() {
        return 50 + random.nextInt(41); // 50..90
    }

    private int randomBuryThreshold() {
        return 1 + random.nextInt(3); // 1..3
    }

    private int randomTableMeatCooldownMs() {
        // Soms snel, soms traag: 1.6s .. 8.2s tussen table-pickup attempts.
        return 1600 + random.nextInt(6601);
    }

    private int randomTableMeatSkipChancePercent() {
        // Dynamische skip-kans per attempt: ~20%..55%.
        return 20 + random.nextInt(36);
    }

    private int boneCount() {
        var bones = Inventory.getAll(i ->
                i != null && i.getName() != null
                        && i.getName().toLowerCase().contains("bones")
                        && i.hasAction("Bury"));
        return bones != null ? bones.size() : 0;
    }

    private boolean withdrawBestMeleeUpgradeFromBank() {
        if (!Bank.isOpen()) return false;

        String bestWeapon = bestUsableBankUpgrade(MELEE_WEAPON_ORDER, true);
        if (bestWeapon != null) {
            Bank.withdraw(bestWeapon, 1);
            return true;
        }

        for (String[] group : MELEE_GEAR_GROUPS) {
            String bestPiece = bestUsableBankUpgrade(group, false);
            if (bestPiece != null) {
                Bank.withdraw(bestPiece, 1);
                return true;
            }
        }
        return false;
    }

    private void withdrawMultipleMeleeUpgradesFromBank(int maxSteps) {
        int steps = 0;
        while (!shouldAbortActions() && Bank.isOpen() && steps < Math.max(1, maxSteps) && !Inventory.isFull()) {
            if (!withdrawBestMeleeUpgradeFromBank()) {
                break;
            }
            steps++;
            sleep(120, 260);
        }
    }

    private String bestUsableBankUpgrade(String[] orderedBestFirst, boolean weapon) {
        int currentBestTier = bestInventoryOrEquippedTier(orderedBestFirst);
        for (String name : orderedBestFirst) {
            int tier = meleeTier(name);
            if (tier <= currentBestTier) continue;
            if (canUseMeleeTierItem(name, weapon) && Bank.contains(name)) {
                return name;
            }
        }
        return null;
    }

    private int bestInventoryOrEquippedTier(String[] names) {
        int best = -1;
        for (String name : names) {
            if (Inventory.contains(name) || Equipment.contains(name)) {
                best = Math.max(best, meleeTier(name));
            }
        }
        return best;
    }

    private boolean canUseMeleeTierItem(String name, boolean weapon) {
        int required = meleeTierRequiredLevel(name);
        try {
            return Skills.getLevel(weapon ? Skill.ATTACK : Skill.DEFENCE) >= required;
        } catch (Exception e) {
            return required <= 1;
        }
    }

    private int meleeTierRequiredLevel(String name) {
        int tier = meleeTier(name);
        switch (tier) {
            case 7: return 60;
            case 6: return 40;
            case 5: return 30;
            case 4: return 20;
            case 3: return 10;
            case 2: return 5;
            default: return 1;
        }
    }

    private int meleeTier(String name) {
        if (name == null) return -1;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("dragon")) return 7;
        if (n.contains("rune")) return 6;
        if (n.contains("adamant")) return 5;
        if (n.contains("mithril")) return 4;
        if (n.contains("black")) return 3;
        if (n.contains("steel")) return 2;
        if (n.contains("iron")) return 1;
        if (n.contains("bronze") || n.contains("wooden") || n.contains("leather")) return 0;
        return -1;
    }

    private void wearBestMeleeFromInventory() {
        wearBestFromGroup(MELEE_WEAPON_ORDER, true);
        for (String[] group : MELEE_GEAR_GROUPS) {
            wearBestFromGroup(group, false);
        }
    }

    private void wearBestFromGroup(String[] orderedBestFirst, boolean weapon) {
        for (String name : orderedBestFirst) {
            IInventoryItem item = Inventory.getFirst(name);
            if (item == null) continue;
            if (!canUseMeleeTierItem(name, weapon)) continue;
            String action = item.hasAction("Wield") ? "Wield" : (item.hasAction("Wear") ? "Wear" : null);
            if (action == null) continue;
            InventoryActionHelper.interact(config, item, action);
            sleep(400, 700);
            return;
        }
    }

    private boolean isWantedLootName(String rawName) {
        if (rawName == null) return false;
        String name = rawName.trim();
        if (config.barbarianLootCoins() && "Coins".equalsIgnoreCase(name)) return true;
        if (config.barbarianLootBones() && name.toLowerCase().contains("bones")) return true;
        for (String n : BARBARIAN_LOOT_NAMES) {
            if (n.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private void sleep(int min, int max) {
        if (shouldAbortActions()) return;
        try {
            Thread.sleep(randomDelay(min, max));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean shouldAbortActions() {
        return config == null || !config.botEnabled();
    }
}

