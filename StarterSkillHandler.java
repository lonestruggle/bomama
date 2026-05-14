package com.combatbot;

import net.storm.api.Static;
import net.storm.api.game.AttackStyle;
import net.storm.api.game.ICombat;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.storm.api.domain.actors.INPC;
import net.storm.api.domain.actors.IPlayer;
import net.storm.api.domain.items.IInventoryItem;
import net.storm.api.domain.tiles.ITileItem;
import net.storm.api.domain.tiles.ITileObject;
import net.storm.api.domain.widgets.IWidget;
import net.storm.sdk.entities.NPCs;
import net.storm.sdk.entities.Players;
import net.storm.sdk.entities.TileItems;
import net.storm.sdk.entities.TileObjects;
import net.storm.sdk.game.Combat;
import net.storm.sdk.game.Skills;
import net.storm.sdk.input.Keyboard;
import net.storm.sdk.items.Bank;
import net.storm.sdk.items.Equipment;
import net.storm.sdk.items.Inventory;
import net.storm.sdk.widgets.Production;
import net.storm.sdk.widgets.Widgets;
import net.runelite.client.util.Text;

import java.awt.event.KeyEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.function.Predicate;

/**
 * F2P starter-pad: eerste bank (Draynor of Lumbridge trap-bank) → train-spot (Draynor: rats+goblins; Lumbridge: cows → raw beef) →
 * WC/FM … → volledig banken → Adventurer Jon (Claim, betere gear) → bank (rommel weg, warrior uit bank) →
 * (Lumbridge: indien nog &lt;30 gp) Port Sarim goblins → imps-handoff.
 *
 * <p>Melee: per run een willekeurig doel 5–10; Attack, Strength en Defence moeten alle drie dat level halen.</p>
 */
public final class StarterSkillHandler {
    private static final int GENIE_LAMP_ITEM_ID = 2528;

    private static final WorldPoint DRAYNOR_BANK_ANCHOR = new WorldPoint(3092, 3243, 0);
    public static final WorldPoint DRAYNOR_STARTER_TRAIN_CENTER = new WorldPoint(2997, 3199, 0);
    /** Lumbridge starter: koeienveld ten noorden van de rivier (config {@link CombatBotConfig.StarterTrainRegion#LUMBRIDGE_SHEEP}). +4Y t.o.v. vroeger: verder van dichte poort/hel, minder pathing naar onbereikbare koeien. */
    public static final WorldPoint LUMBRIDGE_SHEEP_TRAIN_CENTER = new WorldPoint(3180, 3335, 0);
    /** Zie {@link #starterTrainOverlayCenter(CombatBotConfig)} voor Lumbridge. */
    public static final WorldPoint STARTER_TRAIN_AREA_CENTER = DRAYNOR_STARTER_TRAIN_CENTER;
    public static final int STARTER_TRAIN_AREA_RADIUS = 20;
    /** Goblins ten zuiden/westen van Port Sarim (F2P); alleen voor Lumbridge-starter na doelen. */
    private static final WorldPoint PORT_SARIM_GOBLIN_ANCHOR = new WorldPoint(3021, 3214, 0);
    private static final int NEAR_PORT_SARIM_GOBLINS = 12;
    private static final WorldPoint JON_SPOT = new WorldPoint(3232, 3235, 0);
    private static final int NEAR_BANK = 18;
    private static final int NEAR_SPOT = 14;
    private static final int NEAR_JON = 10;
    /** Max ruwe vleesstacks oppakken (rat + koe raw beef) voordat we weer focussen op combat. */
    private static final int STARTER_RAW_MEAT_PICKUP_CAP = 5;
    private static final int COIN_HANDOFF = 30;
    /** Zie RuneLite AnimationID 10563–10573 (forester/bonfire); marge tot HI voor clientvarianten. */
    private static final int BONFIRE_LO = 10563;
    private static final int BONFIRE_HI = 10580;
    private static final int ANIM_FIRE = 733;

    private final CombatBotConfig config;
    private final AntiBan antiBan;
    private final CombatBotPaint paint;
    private final Random random = new Random();

    private Runnable onHandoffToImps;
    private boolean starterAccountHydrated;
    /** Gezet bij panel “volledige reset”: geen fase uit JSON laden. */
    private boolean skipStarterHydrateFromJson;

    /** Willekeurig 5–10; Attack, Strength en Defence moeten elk ≥ dit level. */
    private int meleeTarget;
    private int attackTarget;
    private int strengthTarget;
    private int defenceTarget;
    private int wcTarget;
    private int fmTarget;

    private enum Phase {
        WALK_DRAYNOR_BANK,
        WALK_TRAIN_SPOT,
        TRAIN_COMBAT_AND_SKILLS,
        WALK_FULL_BANK_PRE_JON,
        FULL_DEPOSIT_PRE_JON,
        WALK_JON,
        JON_TALK_CLAIM,
        POST_JON_DRAYNOR_BANK,
        POST_JON_EQUIP_AND_COINS,
        FARM_GOBLINS_FOR_30GP,
        HANDOFF_IMPS
    }

    private Phase phase = Phase.WALK_DRAYNOR_BANK;
    private Phase lastLoggedPhase;
    private long lastTravelMs;
    /** Jon Claim: geen NPC-spam; dialoog door widgets + spatie. */
    private long lastJonDialogueClickMs;
    private long jonLastNpcOpenAttemptMs;
    private boolean jonDialogueWasOpen;
    private long jonNoDialogueSinceMs;
    private long lastDeferLogMs;
    private long lastTrainCombatLogMs;
    private long lastWcFmBlockLogMs;
    private WorldPoint bonfireAnchor;
    private boolean spaceHeld;
    /** Na {@code Tinderbox.useOn(log)}: als na wachttijd nog geen vuur → tellen; bij herhaald falen andere train-tegel. */
    private long firemakingAttemptStartedMs;
    private int starterConsecutiveFireFailures;
    private static final int STARTER_FIRE_FAIL_BEFORE_RELOC = 3;
    private static final long FIREMAKING_OUTCOME_WAIT_MS = 3200;
    /** OSRS game-chat "can't light a fire here" — meteen verplaatsen i.p.v. wachten op timeout-teller. */
    private boolean starterCantLightHerePending;
    /** Game-message "You finish adding the logs to the fire." — forceer direct uit bonfire-wacht. */
    private boolean starterBonfireFinishedNoMoreLogs;

    /** Zelfde basis als {@link WoodcutterHandler}: geen interact-spam. */
    private long lastStarterInteractMs;

    private static final long DEFER_LOG_THROTTLE_MS = 900;
    private static final long TRAIN_COMBAT_LOG_MS = 1500;
    /** Minimaal geschatte HP voordat noodpad stopt (eten/koken/boom). */
    private static final int CRITICAL_HP_THRESHOLD = 7;
    private static final long STARTER_INTERACT_COOLDOWN_MS = 1200;
    private long lastStarterStyleSwitchMs;
    private static final long MELEE_STYLE_SWITCH_COOLDOWN_MS = 500;
    private long lastMeleeStyleDebugLogMs;
    private int lastMeleeStyleDebugKey = Integer.MIN_VALUE;
    private static final long JON_DIALOGUE_CLICK_MS = 420;
    /** Na laatste zichtbare dialoog: kort wachten (tussen pagina's) voordat we Jon als klaar beschouwen. */
    private static final long JON_DIALOGUE_DONE_QUIET_MS = 2200;
    /** Als dialoog niet opent: hooguit opnieuw op Jon klikken na deze pauze. */
    private static final long JON_NPC_RETRY_MS = 5500;

    /** Wandeldoel bij eerste bank → train (±10 tiles rond center); null = opnieuw rollen. */
    private WorldPoint trainWalkDestination;
    /** Na start koken bij lage HP: door tot alle raw meat gekookt is (ook als HP weer &gt; 55%). */
    private boolean starterRawMeatCookBatch;
    public StarterSkillHandler(CombatBotConfig config, AntiBan antiBan, CombatBotPaint paint) {
        this.config = config;
        this.antiBan = antiBan;
        this.paint = paint;
        rollTargets();
    }

    public void setOnHandoffToImps(Runnable onHandoffToImps) {
        this.onHandoffToImps = onHandoffToImps;
    }

    private void rollTargets() {
        meleeTarget = 5 + random.nextInt(6);
        attackTarget = meleeTarget;
        strengthTarget = meleeTarget;
        defenceTarget = meleeTarget;
        wcTarget = 5 + random.nextInt(6);
        fmTarget = 5 + random.nextInt(6);
        DebugLog.log("StarterSkill", "Doelen: melee Att/Str/Def elk ≥" + meleeTarget + " wc=" + wcTarget + " fm=" + fmTarget);
    }

    private void applyAccountSpecificMeleeTargetsIfPresent(String rsn) {
        if (rsn == null || rsn.trim().isEmpty()) {
            return;
        }
        for (ManagedJagexAccountsStore.ManagedJagexAccountRow row :
                ManagedJagexAccountsStore.parseRows(config.managedJagexAccountsBlob())) {
            if (row == null || row.displayName == null) {
                continue;
            }
            if (!row.displayName.trim().equalsIgnoreCase(rsn.trim())) {
                continue;
            }
            if (row.targetAttackLevel > 0) {
                attackTarget = row.targetAttackLevel;
            }
            if (row.targetStrengthLevel > 0) {
                strengthTarget = row.targetStrengthLevel;
            }
            if (row.targetDefenceLevel > 0) {
                defenceTarget = row.targetDefenceLevel;
            }
            meleeTarget = Math.max(attackTarget, Math.max(strengthTarget, defenceTarget));
            DebugLog.log("StarterSkill", "Account doelen toegepast: att/str/def="
                    + attackTarget + "/" + strengthTarget + "/" + defenceTarget);
            return;
        }
    }

    public void resetState() {
        skipStarterHydrateFromJson = false;
        starterAccountHydrated = false;
        phase = Phase.WALK_DRAYNOR_BANK;
        lastLoggedPhase = null;
        lastTravelMs = 0;
        lastJonDialogueClickMs = 0;
        jonLastNpcOpenAttemptMs = 0;
        jonDialogueWasOpen = false;
        jonNoDialogueSinceMs = 0;
        lastDeferLogMs = 0;
        lastTrainCombatLogMs = 0;
        lastWcFmBlockLogMs = 0;
        bonfireAnchor = null;
        spaceHeld = false;
        firemakingAttemptStartedMs = 0;
        starterConsecutiveFireFailures = 0;
        starterCantLightHerePending = false;
        starterBonfireFinishedNoMoreLogs = false;
        lastStarterInteractMs = 0;
        lastStarterStyleSwitchMs = 0;
        lastMeleeStyleDebugLogMs = 0;
        lastMeleeStyleDebugKey = Integer.MIN_VALUE;
        trainWalkDestination = null;
        starterRawMeatCookBatch = false;
        rollTargets();
    }

    /**
     * Panel/volledige reset: starter opnieuw vanaf het begin zonder opgeslagen fase uit JSON te herstellen.
     */
    public void resetStateForPanel() {
        resetState();
        skipStarterHydrateFromJson = true;
    }

    private String tryLocalRsn(IPlayer local) {
        if (local == null || local.getName() == null) {
            return null;
        }
        String n = Text.removeTags(local.getName()).trim();
        return n.isEmpty() ? null : n;
    }

    private void ensureStarterAccountHydrated(IPlayer local) {
        if (skipStarterHydrateFromJson || starterAccountHydrated) {
            return;
        }
        String rsn = tryLocalRsn(local);
        if (rsn == null) {
            return;
        }
        applyAccountSpecificMeleeTargetsIfPresent(rsn);
        AccountStateJsonStore.AccountEntry e = AccountStateJsonStore.getEntry(rsn);
        starterAccountHydrated = true;
        if (e == null) {
            return;
        }
        if (e.jonClaimComplete && phase.compareTo(Phase.POST_JON_DRAYNOR_BANK) < 0) {
            phase = Phase.POST_JON_DRAYNOR_BANK;
            DebugLog.log("StarterSkill", "JSON: Jon al geclaimd → POST_JON_DRAYNOR_BANK");
        }
        if (e.starterPhase != null && !e.starterPhase.isEmpty()) {
            try {
                Phase saved = Phase.valueOf(e.starterPhase);
                if (!e.jonClaimComplete || saved.compareTo(Phase.POST_JON_DRAYNOR_BANK) >= 0) {
                    phase = saved;
                    DebugLog.log("StarterSkill", "JSON: fase hersteld → " + phase);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void persistStarterProgressToJson(IPlayer local) {
        String rsn = tryLocalRsn(local);
        if (rsn == null) {
            return;
        }
        AccountStateJsonStore.update(rsn, e -> {
            e.displayName = rsn;
            e.starterPhase = phase.name();
        });
    }

    private void markJonClaimCompleteInJson(IPlayer local) {
        String rsn = tryLocalRsn(local);
        if (rsn == null) {
            return;
        }
        AccountStateJsonStore.update(rsn, e -> {
            e.displayName = rsn;
            e.jonClaimComplete = true;
        });
    }

    /** Overlay-center: Draynor of Lumbridge koeienveld. */
    public static WorldPoint starterTrainOverlayCenter(CombatBotConfig c) {
        if (c != null && c.starterTrainRegion() == CombatBotConfig.StarterTrainRegion.LUMBRIDGE_SHEEP) {
            return LUMBRIDGE_SHEEP_TRAIN_CENTER;
        }
        return DRAYNOR_STARTER_TRAIN_CENTER;
    }

    private boolean lumbridgeSheepTrain() {
        return config.starterTrainRegion() == CombatBotConfig.StarterTrainRegion.LUMBRIDGE_SHEEP;
    }

    private WorldPoint trainAreaCenter() {
        return lumbridgeSheepTrain() ? LUMBRIDGE_SHEEP_TRAIN_CENTER : DRAYNOR_STARTER_TRAIN_CENTER;
    }

    /** Goblin-farm: Draynor = train-spot; Lumbridge = Port Sarim (geen goblins op train-spot). */
    private WorldPoint goblinFarmCenter() {
        return lumbridgeSheepTrain() ? PORT_SARIM_GOBLIN_ANCHOR : trainAreaCenter();
    }

    private boolean inGoblinFarmZone(WorldPoint me) {
        if (me == null) return false;
        WorldPoint c = goblinFarmCenter();
        // Lumbridge: zelfde radius als aankomst bij goblins (geen “in zone” op verkeerde kusttiles / docks).
        int r = lumbridgeSheepTrain() ? NEAR_PORT_SARIM_GOBLINS : STARTER_TRAIN_AREA_RADIUS;
        return near(me, c, r);
    }

    /** Zelfde NPC-combat detectie als account-wissel / panel-logout ({@link AccountSwitchCombatGate}). */
    private static boolean inNpcCombat() {
        return AccountSwitchCombatGate.isInNpcCombat();
    }

    private static boolean isUnderNpcAttackNow(IPlayer local) {
        if (local == null) {
            return false;
        }
        try {
            INPC attacker = NPCs.getNearest(npc -> npc != null
                    && !npc.isDead()
                    && npc.isInteracting()
                    && npc.getInteracting() != null
                    && npc.getInteracting() == local.getWrapped());
            return attacker != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void logDeferThrottled(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastDeferLogMs >= DEFER_LOG_THROTTLE_MS) {
            lastDeferLogMs = now;
            DebugLog.log("StarterSkill", "defer (NPC-combat): " + reason + " phase=" + phase);
        }
    }

    /**
     * @return delay (&gt;=0) als we bank/lopen/Jon moeten uitstellen; -1 als we verder mogen.
     */
    private int deferNonCombatIfInFight(String reason) {
        if (!inNpcCombat()) {
            return -1;
        }
        logDeferThrottled(reason);
        paint.setCurrentStatus("Starter: NPC-gevecht — wacht…");
        return gameTickDelay();
    }

    private void syncStarterOverlay() {
        paint.setStarterOverlayDebug(phase.name(), inNpcCombat());
        paint.setStarterSkillGoals(meleeTarget, wcTarget, fmTarget);
    }

    private void logPhaseIfChanged() {
        if (phase != lastLoggedPhase) {
            lastLoggedPhase = phase;
            IPlayer lp = Players.getLocal();
            if (lp != null) {
                persistStarterProgressToJson(lp);
            }
            String loc = "?";
            if (lp != null && lp.getWorldLocation() != null) {
                WorldPoint w = lp.getWorldLocation();
                loc = w.getX() + "," + w.getY() + "," + w.getPlane();
            }
            DebugLog.log("StarterSkill", "phase -> " + phase + " tile=" + loc + " npcCombat=" + inNpcCombat());
        }
    }

    private static boolean phaseMustDeferBankWalkJon(Phase p) {
        switch (p) {
            case WALK_DRAYNOR_BANK:
            case WALK_TRAIN_SPOT:
            case WALK_FULL_BANK_PRE_JON:
            case FULL_DEPOSIT_PRE_JON:
            case WALK_JON:
            case JON_TALK_CLAIM:
            case POST_JON_DRAYNOR_BANK:
            case POST_JON_EQUIP_AND_COINS:
            case HANDOFF_IMPS:
                return true;
            default:
                return false;
        }
    }

    /** ~1 OSRS game tick (600 ms) + lichte variatie — geen dubbele acties binnen dezelfde tick. */
    private int gameTickDelay() {
        return antiBan.varyDelay(600 + random.nextInt(101));
    }

    /**
     * Wacht bij bewegen/animatie/cooldown — zelfde idee als {@link WoodcutterHandler#handleChopping()}.
     *
     * @return wacht-tijd (&gt;=0) of -1 als er nu geklikt mag worden.
     */
    private int waitStarterInteractCooldown(IPlayer local) {
        if (local != null) {
            if (local.isAnimating()) {
                return antiBan.varyDelay(600 + random.nextInt(601));
            }
            if (local.isMoving()) {
                return antiBan.varyDelay(400 + random.nextInt(401));
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastStarterInteractMs < STARTER_INTERACT_COOLDOWN_MS) {
            return antiBan.varyDelay(400 + random.nextInt(401));
        }
        int delayMin = config.wcInteractDelayMin();
        int delayMax = config.wcInteractDelayMax();
        if (delayMin > 0 && delayMax > 0 && delayMax >= delayMin) {
            long timeSinceInteract = now - lastStarterInteractMs;
            int requiredDelay = delayMin + random.nextInt(delayMax - delayMin + 1);
            if (timeSinceInteract < requiredDelay) {
                return antiBan.varyDelay(400 + random.nextInt(401));
            }
        }
        return -1;
    }

    private void markStarterInteract() {
        lastStarterInteractMs = System.currentTimeMillis();
    }

    private int afterStarterInteractDelay() {
        return antiBan.varyDelay(1200 + random.nextInt(801));
    }

    private int rDelay(int a, int b) {
        return a + random.nextInt(Math.max(1, b - a));
    }

    private boolean near(WorldPoint p, WorldPoint q, int d) {
        return p != null && q != null && p.distanceTo(q) <= d;
    }

    /** Zelfde radius als starter overlay — binnen zone: train/combat/WC zonder eerst één vaste tile. */
    private boolean inStarterTrainZone(WorldPoint me) {
        return near(me, trainAreaCenter(), STARTER_TRAIN_AREA_RADIUS);
    }

    private WorldPoint walkJitterAnchor() {
        if (phase == Phase.FARM_GOBLINS_FOR_30GP) {
            return goblinFarmCenter();
        }
        return trainAreaCenter();
    }

    private void rollTrainWalkDestination() {
        WorldPoint c = walkJitterAnchor();
        int dx = random.nextInt(21) - 10;
        int dy = random.nextInt(21) - 10;
        trainWalkDestination = new WorldPoint(c.getX() + dx, c.getY() + dy, c.getPlane());
    }

    private WorldPoint destinationForTrainWalk() {
        if (trainWalkDestination == null) {
            rollTrainWalkDestination();
        }
        return trainWalkDestination;
    }

    private void noteFiremakingAttempt() {
        firemakingAttemptStartedMs = System.currentTimeMillis();
    }

    /**
     * Na een FM-poging (tinder+log): als er na {@link #FIREMAKING_OUTCOME_WAIT_MS} nog geen vuur in de buurt is,
     * telt dat als mislukking. Na {@link #STARTER_FIRE_FAIL_BEFORE_RELOC} mislukkingen: nieuwe willekeurige tegel in de train-zone en lopen.
     *
     * @return &gt; 0 als we verplaatsen (caller moet direct returnen)
     */
    private int checkFiremakingFailureAndMaybeRelocate(IPlayer local) {
        if (local == null || local.getWorldLocation() == null) {
            return 0;
        }
        WorldPoint me = local.getWorldLocation();
        if (findNearestLitFireNear(me, 12) != null) {
            starterConsecutiveFireFailures = 0;
            firemakingAttemptStartedMs = 0;
            return 0;
        }
        if (firemakingAttemptStartedMs <= 0L) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - firemakingAttemptStartedMs;
        if (elapsed < FIREMAKING_OUTCOME_WAIT_MS) {
            return 0;
        }
        firemakingAttemptStartedMs = 0L;
        starterConsecutiveFireFailures++;
        DebugLog.log("StarterSkill", "FM: nog geen vuur na poging (" + starterConsecutiveFireFailures
                + "/" + STARTER_FIRE_FAIL_BEFORE_RELOC + ")");
        if (starterConsecutiveFireFailures < STARTER_FIRE_FAIL_BEFORE_RELOC) {
            return 0;
        }
        starterConsecutiveFireFailures = 0;
        bonfireAnchor = null;
        trainWalkDestination = null;
        rollTrainWalkDestination();
        paint.setCurrentStatus("Starter: andere plek voor vuur (geen vuur na FM)");
        DebugLog.log("StarterSkill", "FM: " + STARTER_FIRE_FAIL_BEFORE_RELOC + "x geen vuur — nieuwe train-locatie");
        walk(destinationForTrainWalk());
        return gameTickDelay();
    }

    /**
     * OSRS game-chat bij verboden vuur-tegel; wordt vanuit {@link CombatBotPlugin} doorgestuurd.
     */
    public void onGameMessage(String message) {
        if (message == null) {
            return;
        }
        String lower = message.trim().toLowerCase(Locale.ROOT).replace('\u2019', '\'');
        boolean vanillaCantLight = lower.contains("can't light a fire") || lower.contains("cannot light a fire")
                || lower.contains("you can't light") || lower.contains("unable to light a fire");
        if (vanillaCantLight) {
            starterCantLightHerePending = true;
            bonfireAnchor = null;
        }
        if (lower.contains("you finish adding the logs to the fire")) {
            starterBonfireFinishedNoMoreLogs = true;
            bonfireAnchor = null;
            firemakingAttemptStartedMs = 0L;
        }
    }

    /**
     * @return &gt; 0 als we nu verplaatsen (caller moet returnen)
     */
    private int tryRelocateAfterStarterCantLightGameMessage(IPlayer local) {
        if (!starterCantLightHerePending || local == null) {
            return 0;
        }
        starterCantLightHerePending = false;
        firemakingAttemptStartedMs = 0L;
        starterConsecutiveFireFailures = 0;
        bonfireAnchor = null;
        trainWalkDestination = null;
        rollTrainWalkDestination();
        paint.setCurrentStatus("Starter: andere plek (vuur mag hier niet)");
        DebugLog.log("StarterSkill", "FM: spel weigerde vuur — verplaatsen");
        walk(destinationForTrainWalk());
        return gameTickDelay();
    }

    /**
     * Lange afstanden: zelfde patroon als elders — herklik tijdens lopen ({@link TravelWalkHelper}) i.p.v. wachten tot stilstand.
     */
    private void walk(WorldPoint dest) {
        if (dest == null) return;
        long now = System.currentTimeMillis();
        if (!TravelWalkHelper.reclickReady(lastTravelMs, config)) {
            return;
        }
        MovementHelper.walkTo(dest);
        lastTravelMs = now;
    }

    /** Zelfde Draynor-illusion coords als {@link WoodcutterHandler#isFakeTree}. */
    private static boolean isDraynorFakeTree(WorldPoint point) {
        if (point == null) {
            return false;
        }
        int x = point.getX();
        int y = point.getY();
        return (x == 3086 && y == 3244)
                || (x == 3086 && y == 3243)
                || (x == 3085 && y == 3243)
                || (x == 3085 && y == 3244);
    }

    /** Beste boom voor huidig WC-level (Tree / Oak / Willow …), geen willow bij lvl &lt; 30. */
    private ITileObject findChoppableTreeNearTrain() {
        int wc = skill(Skill.WOODCUTTING);
        String kw = WoodcutterConfig.getBestTreeForLevel(wc);
        return TileObjects.getNearest(obj ->
                obj != null
                        && obj.getName() != null
                        && WoodcutterConfig.matchesTreeObjectName(obj.getName(), kw)
                        && !obj.getName().toLowerCase(Locale.ROOT).contains("dead")
                        && !isDraynorFakeTree(obj.getWorldLocation())
                        && obj.hasAction("Chop down")
                        && obj.getWorldLocation() != null
                        && obj.getWorldLocation().distanceTo(trainAreaCenter()) <= STARTER_TRAIN_AREA_RADIUS);
    }

    private boolean isHpBelowAbsolute(int minHp) {
        try {
            double pct = Combat.getHealthPercent();
            int lvl = Skills.getLevel(Skill.HITPOINTS);
            int maxHp = 10 + lvl + (lvl / 10);
            if (maxHp < 1) {
                maxHp = 10;
            }
            int cur = (int) (pct * maxHp / 100.0);
            return cur < minHp;
        } catch (Throwable t) {
            return Combat.getHealthPercent() < 25.0;
        }
    }

    /**
     * Bij &lt;7 HP: eerst gevecht uit, dan eten, dan koken; boom alleen voor logs/vuur.
     */
    private int tryCriticalHpSurvival(IPlayer local) {
        if (!isHpBelowAbsolute(CRITICAL_HP_THRESHOLD)) {
            return 0;
        }
        if (inNpcCombat()) {
            paint.setCurrentStatus("Starter: <7 HP — gevecht afmaken…");
            DebugLog.log("StarterSkill", "HP<" + CRITICAL_HP_THRESHOLD + ": wacht op einde NPC-combat");
            return gameTickDelay();
        }
        IInventoryItem eat = Inventory.getFirst(it ->
                it != null && it.getActions() != null
                        && Arrays.stream(it.getActions()).anyMatch(a -> a != null && a.equalsIgnoreCase("Eat")));
        if (eat != null) {
            int w = waitStarterInteractCooldown(local);
            if (w >= 0) {
                return w;
            }
            eat.interact("Eat");
            markStarterInteract();
            paint.setCurrentStatus("Starter: eten (nood <7 HP)");
            DebugLog.log("StarterSkill", "HP<" + CRITICAL_HP_THRESHOLD + ": Eat " + eat.getName());
            return afterStarterInteractDelay();
        }
        // Geen food: WC/FM-regen wordt in train-loop afgehandeld; hier alleen nog koken als fallback.
        return tryCookRatOnBonfire(local, true);
    }

    private boolean hasAnyEatFood() {
        return Inventory.getFirst(it ->
                it != null && it.getActions() != null
                        && Arrays.stream(it.getActions()).anyMatch(a -> a != null && a.equalsIgnoreCase("Eat"))) != null;
    }

    /**
     * HP &lt; 7 en geen Eat-food: passief HP laten terugkomen met WC/FM/bonfire (geen combat).
     */
    private int tryHpRegenViaWcFmWhenNoFood(IPlayer local, WorldPoint me) {
        if (!isHpBelowAbsolute(CRITICAL_HP_THRESHOLD)) {
            return 0;
        }
        if (hasAnyEatFood()) {
            return 0;
        }
        if (inNpcCombat()) {
            return 0;
        }
        return starterTrainBonfireWcFm(local, me, true);
    }

    /**
     * Welke melee-stijl: onder {@link #meleeTarget} de skill met het <em>laagste level</em> (Attack / Strength /
     * Defence → Accurate / Aggressive / Defensive). Zo verlaat je Aggressive zodra Strength boven het doel zit
     * maar Attack of Defence nog niet. Bij gelijke levels: voorkeur Attack → Strength → Defence.
     * Gemapt naar {@link AttackStyle#FIRST}/{@link AttackStyle#SECOND}/{@link AttackStyle#THIRD}.
     */
    private int pickMeleeStyleChildForLevels() {
        CombatBotConfig.MeleeTrainingStyle forced = config.starterMeleeTrainingStyle();
        if (forced != null && forced != CombatBotConfig.MeleeTrainingStyle.BALANCED) {
            switch (forced) {
                case ATTACK:
                    return 0;
                case STRENGTH:
                    return 1;
                case DEFENCE:
                    return 2;
                default:
                    break;
            }
        }
        int atk = skill(Skill.ATTACK);
        int str = skill(Skill.STRENGTH);
        int def = skill(Skill.DEFENCE);
        int tAtk = attackTarget;
        int tStr = strengthTarget;
        int tDef = defenceTarget;
        // Def al op doel maar Att/Str niet: geen pure Defensive-stijl forceren
        if (def >= tDef && (atk < tAtk || str < tStr)) {
            if (atk < str) {
                return 0;
            }
            if (str < atk) {
                return 1;
            }
            return 0;
        }
        int candAtk = atk < tAtk ? atk : Integer.MAX_VALUE;
        int candStr = str < tStr ? str : Integer.MAX_VALUE;
        int candDef = def < tDef ? def : Integer.MAX_VALUE;
        int minNeed = Math.min(candAtk, Math.min(candStr, candDef));
        if (minNeed < Integer.MAX_VALUE) {
            if (minNeed == candAtk) {
                return 0;
            }
            if (minNeed == candStr) {
                return 1;
            }
            return 2;
        }
        int min = Math.min(atk, Math.min(str, def));
        if (atk == min) {
            return 0;
        }
        if (str == min) {
            return 1;
        }
        return 2;
    }

    /**
     * Storm {@link AttackStyle}: op 4-stijl zwaarden is {@link AttackStyle#THIRD} de <em>derde</em> knop (vaak Slash = Strength),
     * niet Block (Defence). Defence is meestal de <em>vierde</em> knop → {@code FOURTH} als de enum bestaat.
     */
    private static AttackStyle meleeStyleIndexToAttackStyle(int idx) {
        switch (idx) {
            case 0:
                return AttackStyle.FIRST;
            case 1:
                return AttackStyle.SECOND;
            case 2:
                return fourthOrThirdAttackStyleEnum();
            default:
                return AttackStyle.UNKNOWN;
        }
    }

    private static AttackStyle fourthOrThirdAttackStyleEnum() {
        try {
            return AttackStyle.valueOf("FOURTH");
        } catch (IllegalArgumentException e) {
            return AttackStyle.THIRD;
        }
    }

    /**
     * XP ziet er scheef uit (bijv. veel Strength, weinig Attack) terwijl de Storm-API soms toch {@code cur == want} meldt.
     */
    private boolean meleeCombatXpLooksSkewed() {
        int a = skill(Skill.ATTACK);
        int s = skill(Skill.STRENGTH);
        int d = skill(Skill.DEFENCE);
        int t = meleeTarget;
        if (s >= t && (a < t || d < t)) {
            return true;
        }
        int mx = Math.max(a, Math.max(s, d));
        int mn = Math.min(a, Math.min(s, d));
        return mx - mn >= 6;
    }

    /**
     * Eerst tekst (Stab / Lunge / Block — werkt bij 4-stijl zwaard), daarna child-indices.
     * Vier stijlen: knop 0–3 = Stab, Lunge, Slash, Block — {@code wantIdx==2} (Defence) moet <em>vierde</em> knop zijn,
     * niet de derde (Slash = Strength).
     */
    private boolean tryClickCombatStyleWidgetBySlot(int slot0Attack1Str2Def) {
        if (slot0Attack1Str2Def < 0 || slot0Attack1Str2Def > 2) {
            return false;
        }
        if (tryClickCombatStyleByStyleText(slot0Attack1Str2Def)) {
            return true;
        }
        return tryClickCombatStyleByNumericSlot(slot0Attack1Str2Def);
    }

    /** Fysieke knop-index op het combat-paneel (0-based). Bij 4-stijl melee: Defence = slot 3 (Block), niet 2 (Slash). */
    private static int physicalCombatButtonIndex(int wantAttackStrDef) {
        if (wantAttackStrDef == 2) {
            return 3;
        }
        return wantAttackStrDef;
    }

    private boolean tryClickCombatStyleByNumericSlot(int wantIdx) {
        int phys = physicalCombatButtonIndex(wantIdx);
        int[][] patterns4 = {
                {7, 11, 15, 19},
                {4, 8, 12, 16},
                {6, 10, 14, 18},
                {5, 9, 13, 17},
                {3, 7, 11, 15},
        };
        int[][] patterns3 = {
                {7, 11, 15},
                {4, 8, 12},
                {6, 10, 14},
                {8, 12, 16},
                {3, 7, 11},
        };
        int[] groups = {593, 467};
        for (int g : groups) {
            for (int[] p : patterns4) {
                if (phys >= p.length) {
                    continue;
                }
                try {
                    IWidget w = Widgets.get(g, p[phys]);
                    if (w != null && !w.isHidden()) {
                        w.interact(0);
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (wantIdx == 2) {
                continue;
            }
            for (int[] p : patterns3) {
                if (wantIdx >= p.length) {
                    continue;
                }
                try {
                    IWidget w = Widgets.get(g, p[wantIdx]);
                    if (w != null && !w.isHidden()) {
                        w.interact(0);
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    /**
     * Zoek op zichtbare tekst (schermit, bijl, zwaard — verschillende wapens).
     */
    private boolean tryClickCombatStyleByStyleText(int wantIdx) {
        String[][] keys = {
                {"stab", "accur", "chop", "pound", "punch", "strike", "reap"},
                {"lunge", "slash", "aggress", "hack", "bash", "kick"},
                {"block", "defens", "fend"},
        };
        if (wantIdx < 0 || wantIdx >= keys.length) {
            return false;
        }
        String[] need = keys[wantIdx];
        try {
            for (int g : new int[]{593, 467}) {
                IWidget root = Widgets.get(g, 0);
                if (root == null || root.isHidden()) {
                    continue;
                }
                for (IWidget w : flattenWidgets(root)) {
                    if (w == null || w.isHidden()) {
                        continue;
                    }
                    String t = w.getText();
                    if (t == null || t.isEmpty()) {
                        continue;
                    }
                    String low = t.toLowerCase(Locale.ROOT);
                    for (String k : need) {
                        if (low.contains(k)) {
                            w.interact(0);
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Schakelt melee-stijl via {@link ICombat#setAttackStyle(AttackStyle)} plus widget-fallback op de combat-tab.
     *
     * @see AttackStyle
     * @see ICombat#getAttackStyle()
     */
    private int trySwitchStarterMeleeStyle() {
        int idx = pickMeleeStyleChildForLevels();
        AttackStyle want = meleeStyleIndexToAttackStyle(idx);
        if (want == AttackStyle.UNKNOWN) {
            return 0;
        }
        long now = System.currentTimeMillis();
        if (now - lastStarterStyleSwitchMs < MELEE_STYLE_SWITCH_COOLDOWN_MS) {
            return 0;
        }
        try {
            ICombat combat = Static.getCombat();
            if (combat == null) {
                return 0;
            }
            AttackStyle cur = combat.getAttackStyle();
            boolean skewed = meleeCombatXpLooksSkewed();
            // Al op gewenste stijl: stop. XP kan nog "scheef" zijn (hoge Str, lage Def) terwijl Block al goed staat —
            // dan moet je alleen nog vechten, niet elke tick opnieuw klikken.
            if (cur != AttackStyle.UNKNOWN && cur == want) {
                return 0;
            }
            if (cur != AttackStyle.UNKNOWN) {
                combat.setAttackStyle(want);
            }
            boolean clicked = tryClickCombatStyleWidgetBySlot(idx);
            lastStarterStyleSwitchMs = now;
            String[] labels = {"Attack", "Strength", "Defence"};
            paint.setCurrentStatus("Starter: stijl → " + labels[idx] + (clicked ? "" : " (API)"));
            int dbgKey = Objects.hash(idx, cur, skewed, clicked);
            long dbgNow = System.currentTimeMillis();
            if (config.starterMeleeStyleDebug()
                    && (dbgKey != lastMeleeStyleDebugKey || dbgNow - lastMeleeStyleDebugLogMs >= 2500L)) {
                lastMeleeStyleDebugKey = dbgKey;
                lastMeleeStyleDebugLogMs = dbgNow;
                DebugLog.log("StarterSkill", "melee style → " + want + " (api cur=" + cur
                        + (skewed ? ", skewed→widget" : "")
                        + (clicked ? ", widget click" : "")
                        + ") Att/Str/Def " + skill(Skill.ATTACK) + "/" + skill(Skill.STRENGTH) + "/" + skill(Skill.DEFENCE)
                        + ", doel " + meleeTarget);
            }
            return gameTickDelay();
        } catch (Throwable t) {
            DebugLog.log("StarterSkill", "setAttackStyle: " + t.getMessage());
            return 0;
        }
    }

    /** Onder eatPercent: eet gekookt vlees / ander Eat-voedsel (Combat-eatPercent in config). Ook tijdens NPC-gevecht. */
    private int tryEatWhenLowHp(IPlayer local) {
        try {
            if (Combat.getHealthPercent() >= config.eatPercent()) {
                return 0;
            }
        } catch (Throwable t) {
            return 0;
        }
        IInventoryItem food = Inventory.getFirst(it ->
                it != null && it.getActions() != null
                        && Arrays.stream(it.getActions()).anyMatch(a -> a != null && a.equalsIgnoreCase("Eat")));
        if (food == null) {
            return 0;
        }
        int w = waitStarterInteractCooldown(local);
        if (w >= 0) {
            return w;
        }
        food.interact("Eat");
        markStarterInteract();
        paint.setCurrentStatus("Starter: eten (lage HP)");
        return afterStarterInteractDelay();
    }

    /**
     * Bij {@link #hpLowForCook()} eerst gekookt eten (Cooked meat e.d.) voordat we raw op het vuur doen.
     */
    private int tryEatCookedBeforeCookRaw(IPlayer local) {
        if (!hpLowForCook()) {
            return 0;
        }
        if (!hasStarterRawMeatForCooking()) {
            return 0;
        }
        IInventoryItem food = Inventory.getFirst(it ->
                it != null && it.getName() != null
                        && !it.getName().toLowerCase(Locale.ROOT).contains("raw")
                        && it.getActions() != null
                        && Arrays.stream(it.getActions()).anyMatch(a -> a != null && a.equalsIgnoreCase("Eat")));
        if (food == null) {
            return 0;
        }
        int w = waitStarterInteractCooldown(local);
        if (w >= 0) {
            return w;
        }
        food.interact("Eat");
        markStarterInteract();
        paint.setCurrentStatus("Starter: eten voor koken (lage HP)");
        return afterStarterInteractDelay();
    }

    private boolean isWoodcuttingAxeName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("pickaxe")) return false;
        if (!n.contains("axe")) return false;
        return !n.contains("battleaxe") && !n.contains("battle axe");
    }

    private boolean isStarterSwordLike(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        if (isWoodcuttingAxeName(name)) return false;
        return n.contains("sword") || n.contains("scimitar") || n.contains("dagger") || n.contains("mace");
    }

    private boolean keepForInitialBank(String name) {
        if (name == null) return false;
        if (name.equalsIgnoreCase("Tinderbox")) return true;
        if (isWoodcuttingAxeName(name)) return true;
        return isStarterSwordLike(name);
    }

    private boolean hasWoodcuttingAxeInInventory() {
        return Inventory.getFirst(it -> it != null && it.getName() != null && isWoodcuttingAxeName(it.getName())) != null;
    }

    private boolean hasStarterWeaponInInventory() {
        return Inventory.getFirst(it -> it != null && it.getName() != null && isStarterSwordLike(it.getName())) != null;
    }

    private boolean hasStarterMeleeWeaponEquipped() {
        return Equipment.contains(item -> item != null && item.getName() != null && isStarterSwordLike(item.getName()));
    }

    private boolean hasWoodcuttingAxeEquippedOrInventory() {
        if (hasWoodcuttingAxeInInventory()) {
            return true;
        }
        return Equipment.contains(item -> item != null && item.getName() != null && isWoodcuttingAxeName(item.getName()));
    }

    /**
     * Bijl + tinderbox + melee-wapen (geëquipeerd of in inv) — genoeg om verder te gaan zonder eerste bankbezoek.
     */
    private boolean starterEssentialToolkitPresent() {
        if (!Inventory.contains("Tinderbox")) {
            return false;
        }
        if (!hasWoodcuttingAxeEquippedOrInventory()) {
            return false;
        }
        return hasStarterMeleeWeaponEquipped() || hasStarterWeaponInInventory();
    }

    /**
     * Items die we tijdens de starter mogen houden (naast toolkit): bones, logs, eten, rat meat, coins, etc.
     * Alles wat hier niet onder valt telt als rommel → bank storten.
     */
    private boolean isStarterAllowedInventoryItem(IInventoryItem it) {
        if (it == null || it.getName() == null) {
            return false;
        }
        String name = it.getName();
        if (keepForInitialBank(name)) {
            return true;
        }
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("burnt")) {
            return false;
        }
        if (n.contains("logs")) {
            return true;
        }
        if (n.contains("bone")) {
            return true;
        }
        if (n.contains("coin")) {
            return true;
        }
        if (n.contains("ashes")) {
            return true;
        }
        if (n.contains("raw rat meat") || n.contains("rat meat") || n.contains("raw meat")
                || n.contains("raw beef")) {
            return true;
        }
        if (n.contains("cooked") && n.contains("meat")) {
            return true;
        }
        try {
            if (it.getActions() != null
                    && Arrays.stream(it.getActions()).anyMatch(a -> a != null && a.equalsIgnoreCase("Eat"))) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean hasUnwantedStarterInventoryItems() {
        List<IInventoryItem> all = Inventory.getAll();
        if (all == null) {
            return false;
        }
        for (IInventoryItem it : all) {
            if (it == null || it.getName() == null) {
                continue;
            }
            if (!isStarterAllowedInventoryItem(it)) {
                return true;
            }
        }
        return false;
    }

    /** Haal bijl + tinder + wapen uit bank als tutorial ze in de bank liet. */
    private void withdrawStarterToolkitFromBank() {
        if (!Bank.isOpen()) {
            return;
        }
        if (!Inventory.contains("Tinderbox") && Bank.contains("Tinderbox")) {
            Bank.withdraw("Tinderbox", 1);
            HumanBanking.pauseBetweenActions();
        }
        if (!hasWoodcuttingAxeInInventory()) {
            String axe = pickBestUsableAxeFromBank();
            if (axe != null) {
                Bank.withdraw(axe, 1);
                HumanBanking.pauseBetweenActions();
            }
        }
        withdrawStarterTieredWeaponUpgradeFromBank();
    }

    /** OSRS WC-level; beste bijl eerst. */
    private static final String[] BANK_AXE_BY_TIER = {
            "Rune axe", "Adamant axe", "Mithril axe", "Black axe", "Steel axe", "Iron axe", "Bronze axe"
    };
    private static final int[] BANK_AXE_WC_REQ = { 41, 31, 21, 11, 6, 1, 1 };

    /** Attack-level; scimitar vóór sword op gelijk tier. */
    private static final String[] BANK_WPN_BY_TIER = {
            "Rune scimitar", "Rune sword", "Rune longsword",
            "Adamant scimitar", "Adamant sword", "Adamant longsword",
            "Mithril scimitar", "Mithril sword", "Mithril longsword",
            "Black scimitar", "Black sword", "Black longsword",
            "Steel scimitar", "Steel sword", "Steel longsword",
            "Iron scimitar", "Iron sword", "Iron longsword",
            "Bronze scimitar", "Bronze sword", "Bronze longsword"
    };
    private static final int[] BANK_WPN_ATK_REQ = {
            40, 40, 40,
            30, 30, 30,
            20, 20, 20,
            10, 10, 10,
            5, 5, 5,
            1, 1, 1,
            1, 1, 1
    };
    /** Zelfde index als {@link #BANK_WPN_BY_TIER}; OSRS: zwaarden/zenith i.p.v. 2h hier. */
    private static final int[] BANK_WPN_STR_REQ = {
            1, 1, 1,
            1, 1, 1,
            1, 1, 1,
            1, 1, 1,
            1, 1, 1,
            1, 1, 1,
            1, 1, 1
    };

    /**
     * Plate per tier (body, legs, helm) — OSRS Defence; eerste tier waar speler aan voldoet + bank heeft stuk.
     */
    private static final String[][] STARTER_PLATE_BY_TIER = {
            {"Rune platebody", "Rune platelegs", "Rune full helm"},
            {"Adamant platebody", "Adamant platelegs", "Adamant full helm"},
            {"Mithril platebody", "Mithril platelegs", "Mithril full helm"},
            {"Steel platebody", "Steel platelegs", "Steel full helm"},
            {"Iron platebody", "Iron platelegs", "Iron full helm"},
            {"Bronze platebody", "Bronze platelegs", "Bronze full helm"},
    };
    private static final int[] STARTER_PLATE_DEF_REQ = {40, 30, 20, 5, 1, 1};
    private static final String[][] STARTER_HELM_BY_TIER = {
            {"Rune full helm", "Rune med helm"},
            {"Adamant full helm", "Adamant med helm"},
            {"Mithril full helm", "Mithril med helm"},
            {"Steel full helm", "Steel med helm"},
            {"Iron full helm", "Iron med helm"},
            {"Bronze full helm", "Bronze med helm"}
    };

    private static final String[] STARTER_LEATHER_BODY_PREF = {"Hardleather body", "Leather body"};
    private static final int[] STARTER_LEATHER_BODY_DEF_REQ = {10, 1};
    private static final String[] STARTER_LEATHER_REST = {"Leather gloves", "Leather boots", "Leather cowl"};
    private static final String[] STARTER_SHIELD_BY_TIER = {
            "Rune kiteshield", "Adamant kiteshield", "Mithril kiteshield", "Steel kiteshield", "Iron kiteshield", "Wooden shield"
    };
    private static final int[] STARTER_SHIELD_DEF_REQ = {40, 30, 20, 5, 1, 1};

    private String pickBestUsableAxeFromBank() {
        int wc = skill(Skill.WOODCUTTING);
        for (int i = 0; i < BANK_AXE_BY_TIER.length; i++) {
            if (wc >= BANK_AXE_WC_REQ[i] && Bank.contains(BANK_AXE_BY_TIER[i])) {
                return BANK_AXE_BY_TIER[i];
            }
        }
        return null;
    }

    private boolean canUseWeaponTier(int i) {
        int atk = skill(Skill.ATTACK);
        int str = skill(Skill.STRENGTH);
        return atk >= BANK_WPN_ATK_REQ[i]
                && str >= BANK_WPN_STR_REQ[i];
    }

    private String pickBestUsableWeaponFromBank() {
        for (int i = 0; i < BANK_WPN_BY_TIER.length; i++) {
            if (canUseWeaponTier(i) && Bank.contains(BANK_WPN_BY_TIER[i])) {
                return BANK_WPN_BY_TIER[i];
            }
        }
        return null;
    }

    /**
     * Beste tier-wapen in de <strong>inventory</strong> dat strikt beter is dan het beste zwaard/scimitar
     * dat we al dragen (Jon-gear zit vaak al op equipment — dan mag een slechter item in de zak niet winnen).
     */
    private String pickStrictlyBetterUsableWeaponInInventoryThanEquipped() {
        int equippedBest = bestTieredMeleeWeaponIndexEquippedOnly();
        String chosen = null;
        int chosenIdx = Integer.MAX_VALUE;
        for (int i = 0; i < BANK_WPN_BY_TIER.length; i++) {
            if (!canUseWeaponTier(i)) {
                continue;
            }
            if (equippedBest != Integer.MAX_VALUE && i >= equippedBest) {
                continue;
            }
            if (!Inventory.contains(BANK_WPN_BY_TIER[i])) {
                continue;
            }
            if (i < chosenIdx) {
                chosenIdx = i;
                chosen = BANK_WPN_BY_TIER[i];
            }
        }
        return chosen;
    }

    private void wieldStarterTierWeaponIfInInventory(String wName) {
        if (wName == null) {
            return;
        }
        IInventoryItem w = Inventory.getFirst(wName);
        if (w != null && w.hasAction("Wield")) {
            w.interact("Wield");
            HumanBanking.pauseWearOrWield();
        }
    }

    /**
     * Beste (laagste index in {@link #BANK_WPN_BY_TIER}) tier-wapen dat we dragen of in inv hebben;
     * {@link Integer#MAX_VALUE} als we geen zwaard/scimitar uit de tier-lijst hebben (bijv. alleen dolk).
     */
    private int bestTieredMeleeWeaponIndexEquippedOrInventory() {
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < BANK_WPN_BY_TIER.length; i++) {
            if (!canUseWeaponTier(i)) {
                continue;
            }
            String n = BANK_WPN_BY_TIER[i];
            if (Inventory.contains(n) || Equipment.contains(n)) {
                best = Math.min(best, i);
            }
        }
        return best;
    }

    /** Zelfde als hierboven, maar alléén equipment (voor wield-beslissing). */
    private int bestTieredMeleeWeaponIndexEquippedOnly() {
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < BANK_WPN_BY_TIER.length; i++) {
            if (!canUseWeaponTier(i)) {
                continue;
            }
            String n = BANK_WPN_BY_TIER[i];
            if (Equipment.contains(n)) {
                best = Math.min(best, i);
            }
        }
        return best;
    }

    /**
     * Trekt strikt beter tier-wapen uit de bank (bijv. ijzer zwaard terwijl er een bronzen dolk in de zak zit).
     */
    private void withdrawStarterTieredWeaponUpgradeFromBank() {
        if (!Bank.isOpen()) {
            return;
        }
        int have = bestTieredMeleeWeaponIndexEquippedOrInventory();
        for (int i = 0; i < BANK_WPN_BY_TIER.length; i++) {
            if (have != Integer.MAX_VALUE && i >= have) {
                break;
            }
            if (!canUseWeaponTier(i)) {
                continue;
            }
            String name = BANK_WPN_BY_TIER[i];
            if (Bank.contains(name)) {
                Bank.withdraw(name, 1);
                HumanBanking.pauseBetweenActions();
                return;
            }
        }
    }

    private void withdrawBestWarriorArmorFromBank() {
        int def = skill(Skill.DEFENCE);
        for (int slot = 0; slot < 2; slot++) {
            for (int t = 0; t < STARTER_PLATE_BY_TIER.length; t++) {
                if (def < STARTER_PLATE_DEF_REQ[t]) {
                    continue;
                }
                String name = STARTER_PLATE_BY_TIER[t][slot];
                if (Bank.contains(name)) {
                    Bank.withdraw(name, 1);
                    HumanBanking.pauseBetweenActions();
                    break;
                }
            }
        }
        for (int t = 0; t < STARTER_HELM_BY_TIER.length; t++) {
            if (def < STARTER_PLATE_DEF_REQ[t]) {
                continue;
            }
            for (String helm : STARTER_HELM_BY_TIER[t]) {
                if (Bank.contains(helm)) {
                    Bank.withdraw(helm, 1);
                    HumanBanking.pauseBetweenActions();
                    t = STARTER_HELM_BY_TIER.length;
                    break;
                }
            }
        }
        for (int i = 0; i < STARTER_LEATHER_BODY_PREF.length; i++) {
            if (def < STARTER_LEATHER_BODY_DEF_REQ[i]) {
                continue;
            }
            String body = STARTER_LEATHER_BODY_PREF[i];
            if (Bank.contains(body)) {
                Bank.withdraw(body, 1);
                HumanBanking.pauseBetweenActions();
                break;
            }
        }
        for (String piece : STARTER_LEATHER_REST) {
            if (def < 1) {
                break;
            }
            if (Bank.contains(piece)) {
                Bank.withdraw(piece, 1);
                HumanBanking.pauseBetweenActions();
            }
        }
    }

    private void wearBestWarriorArmorFromInventory() {
        int def = skill(Skill.DEFENCE);
        for (int slot = 0; slot < 2; slot++) {
            for (int t = 0; t < STARTER_PLATE_BY_TIER.length; t++) {
                if (def < STARTER_PLATE_DEF_REQ[t]) {
                    continue;
                }
                String name = STARTER_PLATE_BY_TIER[t][slot];
                IInventoryItem it = Inventory.getFirst(name);
                if (it == null) {
                    continue;
                }
                if (it.hasAction("Wear")) {
                    it.interact("Wear");
                    HumanBanking.pauseWearOrWield();
                } else if (it.hasAction("Wield")) {
                    it.interact("Wield");
                    HumanBanking.pauseWearOrWield();
                }
                break;
            }
        }
        for (int t = 0; t < STARTER_HELM_BY_TIER.length; t++) {
            if (def < STARTER_PLATE_DEF_REQ[t]) {
                continue;
            }
            for (String helm : STARTER_HELM_BY_TIER[t]) {
                IInventoryItem it = Inventory.getFirst(helm);
                if (it == null) {
                    continue;
                }
                if (it.hasAction("Wear")) {
                    it.interact("Wear");
                    HumanBanking.pauseWearOrWield();
                    t = STARTER_HELM_BY_TIER.length;
                    break;
                }
                if (it.hasAction("Wield")) {
                    it.interact("Wield");
                    HumanBanking.pauseWearOrWield();
                    t = STARTER_HELM_BY_TIER.length;
                    break;
                }
            }
        }
        for (int i = 0; i < STARTER_LEATHER_BODY_PREF.length; i++) {
            if (def < STARTER_LEATHER_BODY_DEF_REQ[i]) {
                continue;
            }
            String body = STARTER_LEATHER_BODY_PREF[i];
            IInventoryItem it = Inventory.getFirst(body);
            if (it != null && it.hasAction("Wear")) {
                it.interact("Wear");
                HumanBanking.pauseWearOrWield();
                break;
            }
        }
        for (String piece : STARTER_LEATHER_REST) {
            IInventoryItem it = Inventory.getFirst(piece);
            if (it != null && it.hasAction("Wear")) {
                it.interact("Wear");
                HumanBanking.pauseWearOrWield();
            }
        }
    }

    private void withdrawBestShieldFromBank() {
        if (!Bank.isOpen()) {
            return;
        }
        int def = skill(Skill.DEFENCE);
        for (int i = 0; i < STARTER_SHIELD_BY_TIER.length; i++) {
            if (def < STARTER_SHIELD_DEF_REQ[i]) {
                continue;
            }
            String name = STARTER_SHIELD_BY_TIER[i];
            if (Bank.contains(name)) {
                Bank.withdraw(name, 1);
                HumanBanking.pauseBetweenActions();
                return;
            }
        }
    }

    private void wearBestShieldFromInventory() {
        int def = skill(Skill.DEFENCE);
        for (int i = 0; i < STARTER_SHIELD_BY_TIER.length; i++) {
            if (def < STARTER_SHIELD_DEF_REQ[i]) {
                continue;
            }
            String name = STARTER_SHIELD_BY_TIER[i];
            IInventoryItem it = Inventory.getFirst(name);
            if (it == null) {
                continue;
            }
            if (it.hasAction("Wear")) {
                it.interact("Wear");
                HumanBanking.pauseWearOrWield();
                return;
            }
            if (it.hasAction("Wield")) {
                it.interact("Wield");
                HumanBanking.pauseWearOrWield();
                return;
            }
        }
    }

    /** Na Jon: beste plate/leer + wapen uit inventory aandoen (vóór bank-trip). */
    private void equipBestGearFromInventoryAfterJon() {
        wearBestWarriorArmorFromInventory();
        wearBestShieldFromInventory();
        wieldStarterTierWeaponIfInInventory(pickStrictlyBetterUsableWeaponInInventoryThanEquipped());
    }

    private int skill(Skill s) {
        try {
            return Skills.getLevel(s);
        } catch (Throwable t) {
            return 1;
        }
    }

    private boolean meleeGoalsMet() {
        return skill(Skill.ATTACK) >= attackTarget
                && skill(Skill.STRENGTH) >= strengthTarget
                && skill(Skill.DEFENCE) >= defenceTarget;
    }

    private boolean wcFmGoalsMet() {
        return skill(Skill.WOODCUTTING) >= wcTarget && skill(Skill.FIREMAKING) >= fmTarget;
    }

    private boolean hasStarterRawMeatForCooking() {
        return Inventory.contains("Raw rat meat")
                || Inventory.contains("Raw beef")
                || Inventory.contains("Raw meat");
    }

    /**
     * Welk ruwe vlees eerst koken: rat → koe (Lumbridge) → generiek raw meat.
     */
    private String pickStarterRawMeatName() {
        if (Inventory.contains("Raw rat meat")) {
            return "Raw rat meat";
        }
        if (Inventory.contains("Raw beef")) {
            return "Raw beef";
        }
        if (Inventory.contains("Raw meat")) {
            return "Raw meat";
        }
        return null;
    }

    /** Totaal ruwe vleesstuks (voor pickup-cap). */
    private int countStarterRawMeatPieces() {
        return Inventory.getCount(true, "Raw rat meat")
                + Inventory.getCount(true, "Raw beef")
                + Inventory.getCount(true, "Raw meat")
                + Inventory.getCount(true, "Rat meat");
    }

    /** Starter: nooit coins storten — gp blijft in inventory. */
    private static boolean isStarterCoinStackName(String name) {
        return name != null && name.equalsIgnoreCase("Coins");
    }

    /**
     * Leegt inventory naar de bank behalve coins (starter: gp niet banken).
     */
    private void depositEntireInventory() {
        List<IInventoryItem> items = Inventory.getAll();
        if (items == null || items.isEmpty()) {
            return;
        }
        List<IInventoryItem> order = new ArrayList<>(items);
        Collections.shuffle(order, random);
        for (IInventoryItem it : order) {
            if (it == null || it.getName() == null) {
                continue;
            }
            if (it.getId() == GENIE_LAMP_ITEM_ID) {
                continue;
            }
            if (isStarterCoinStackName(it.getName())) {
                continue;
            }
            Bank.depositAll(it.getName());
            HumanBanking.pauseBetweenActions();
        }
    }

    /** Eerst alle coins uit de bank — nergens gp achterlaten voor een trip. */
    private void withdrawAllCoinsFromBankIfPresent() {
        if (!Bank.isOpen()) {
            return;
        }
        if (Bank.contains("Coins")) {
            Bank.withdrawAll("Coins");
            HumanBanking.pauseBetweenActions();
        }
    }

    private int loopInitialBank(IPlayer local, WorldPoint me) {
        int dropBurnt = dropBurntMeat(local);
        if (dropBurnt > 0) {
            return dropBurnt;
        }

        if (starterEssentialToolkitPresent() && !hasUnwantedStarterInventoryItems()) {
            phase = Phase.WALK_TRAIN_SPOT;
            rollTrainWalkDestination();
            paint.setLastAntiBanAction("Starter: toolkit OK — overslaan bank");
            paint.setCurrentStatus("Starter: naar train-gebied");
            return gameTickDelay();
        }

        if (lumbridgeSheepTrain()) {
            if (!Bank.isOpen()) {
                if (BankHelper.tryOpenFullBank()) {
                    return gameTickDelay();
                }
                paint.setCurrentStatus("Starter: naar Lumbridge bank (trap)");
                BankHelper.walkToLumbridgeCastleBank();
                return gameTickDelay();
            }
        } else {
            if (!near(me, DRAYNOR_BANK_ANCHOR, NEAR_BANK)) {
                paint.setCurrentStatus("Starter: naar Draynor bank");
                walk(DRAYNOR_BANK_ANCHOR);
                return gameTickDelay();
            }
            if (!Bank.isOpen()) {
                if (BankHelper.tryOpenFullBank()) {
                    return gameTickDelay();
                }
                return gameTickDelay();
            }
        }
        withdrawAllCoinsFromBankIfPresent();
        List<IInventoryItem> toDeposit = new ArrayList<>();
        for (IInventoryItem it : Inventory.getAll()) {
            if (it == null || it.getName() == null) {
                continue;
            }
            if (it.getId() == GENIE_LAMP_ITEM_ID) {
                continue;
            }
            if (isStarterCoinStackName(it.getName())) {
                continue;
            }
            if (isStarterAllowedInventoryItem(it)) {
                continue;
            }
            toDeposit.add(it);
        }
        Collections.shuffle(toDeposit, random);
        for (IInventoryItem it : toDeposit) {
            Bank.depositAll(it.getName());
            HumanBanking.pauseBetweenActions();
        }
        withdrawStarterToolkitFromBank();
        equipBestGearFromInventoryAfterJon();
        HumanBanking.pauseBeforeClose();
        Bank.close();
        HumanBanking.pauseAfterClose();
        phase = Phase.WALK_TRAIN_SPOT;
        rollTrainWalkDestination();
        paint.setLastAntiBanAction("Starter: bank klaar (bijl+tinder+zwaard)");
        return gameTickDelay();
    }

    private int loopWalkTrain(IPlayer local, WorldPoint me) {
        if (inStarterTrainZone(me)) {
            phase = Phase.TRAIN_COMBAT_AND_SKILLS;
            trainWalkDestination = null;
            return gameTickDelay();
        }
        paint.setCurrentStatus("Starter: naar train-gebied");
        walk(destinationForTrainWalk());
        return gameTickDelay();
    }

    private int buryOneBone(IPlayer local) {
        IInventoryItem bone = Inventory.getFirst("Bones");
        if (bone == null) {
            return 0;
        }
        int w = waitStarterInteractCooldown(local);
        if (w >= 0) {
            return w;
        }
        bone.interact("Bury");
        markStarterInteract();
        return afterStarterInteractDelay();
    }

    private int dropBurntMeat(IPlayer local) {
        IInventoryItem burnt = Inventory.getFirst(it -> it != null && it.getName() != null
                && it.getName().toLowerCase(Locale.ROOT).contains("burnt")
                && it.getName().toLowerCase(Locale.ROOT).contains("meat"));
        if (burnt == null) {
            return 0;
        }
        int w = waitStarterInteractCooldown(local);
        if (w >= 0) {
            return w;
        }
        burnt.interact("Drop");
        markStarterInteract();
        return afterStarterInteractDelay();
    }

    private boolean hpLowForCook() {
        try {
            return Combat.getHealthPercent() < 55;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * OSRS "How many would you like to cook?" (make-all): widgetgroep 270 — spatie = bevestigen / Cook All.
     * Zelfde aanpak als {@link FishingHandler#handleCooking()}.
     *
     * @return wacht-tijd (&gt;0) als het scherm open was, anders 0
     */
    private int tryConfirmCookQuantityDialog() {
        IWidget cookQtyRoot = Widgets.get(270, 0);
        if (cookQtyRoot == null || cookQtyRoot.isHidden()) {
            return 0;
        }
        Keyboard.type(String.valueOf((char) KeyEvent.VK_SPACE), false);
        paint.setCurrentStatus("Starter: kook hoeveelheid (spatie)");
        DebugLog.log("StarterSkill", "Cook quantity dialog: space (cook all)");
        return antiBan.varyDelay(1200 + random.nextInt(801));
    }

    /** Alleen als we nog raw vlees hebben (rat / beef / raw meat) — voorkomt vastzitten op het dialoog. */
    private int tryConfirmCookQuantityDialogIfRawMeat() {
        if (!hasStarterRawMeatForCooking()) {
            return 0;
        }
        return tryConfirmCookQuantityDialog();
    }

    private int tryCookRatOnBonfire(IPlayer local) {
        return tryCookRatOnBonfire(local, false);
    }

    /**
     * @param criticalHpMode {@code true} = alleen bij {@link #CRITICAL_HP_THRESHOLD} HP (geen 55%-check); boom = beste voor WC-level.
     */
    private int tryCookRatOnBonfire(IPlayer local, boolean criticalHpMode) {
        if (inNpcCombat()) {
            return 0;
        }
        int cantLightCook = tryRelocateAfterStarterCantLightGameMessage(local);
        if (cantLightCook > 0) {
            return cantLightCook;
        }
        int fmRelocCook = checkFiremakingFailureAndMaybeRelocate(local);
        if (fmRelocCook > 0) {
            return fmRelocCook;
        }
        if (!hasStarterRawMeatForCooking()) {
            starterRawMeatCookBatch = false;
            return 0;
        }
        String rawName = pickStarterRawMeatName();
        if (rawName == null) {
            starterRawMeatCookBatch = false;
            return 0;
        }
        if (criticalHpMode) {
            if (!isHpBelowAbsolute(CRITICAL_HP_THRESHOLD)) {
                return 0;
            }
        } else if (!hpLowForCook() && !starterRawMeatCookBatch) {
            return 0;
        }
        starterRawMeatCookBatch = true;
        int animPre = local.getAnimation();
        if (animPre == 896 || animPre == 897) {
            paint.setCurrentStatus("Starter: koken…");
            return gameTickDelay();
        }
        int qtyDlg = tryConfirmCookQuantityDialog();
        if (qtyDlg > 0) {
            return qtyDlg;
        }
        if (Production.isOpen()) {
            int w = waitStarterInteractCooldown(local);
            if (w >= 0) {
                return w;
            }
            Production.chooseOption(rawName);
            markStarterInteract();
            paint.setCurrentStatus("Starter: Production koken (" + rawName + ")");
            return afterStarterInteractDelay();
        }
        if (!hasLogs()) {
            ITileObject tree = findChoppableTreeNearTrain();
            if (tree != null) {
                int w = waitStarterInteractCooldown(local);
                if (w >= 0) {
                    return w;
                }
                tree.interact("Chop down");
                markStarterInteract();
                paint.setCurrentStatus(criticalHpMode ? "Starter: boom (nood, koken)" : "Starter: boom (koken)");
                DebugLog.log("StarterSkill", "chop for cook: " + (tree.getName() != null ? tree.getName() : "?")
                        + " wc=" + skill(Skill.WOODCUTTING));
                return afterStarterInteractDelay();
            }
            return gameTickDelay();
        }
        if (!Inventory.contains("Tinderbox")) {
            return gameTickDelay();
        }
        WorldPoint m = local.getWorldLocation();
        if (holdTickForStarterFmAnimationIfRelevant(local)) {
            return gameTickDelay();
        }
        ITileObject fire = null;
        if (bonfireAnchor != null) {
            fire = TileObjects.getNearest(obj ->
                    obj != null && obj.getName() != null
                            && (obj.getName().toLowerCase(Locale.ROOT).contains("fire")
                            || obj.getName().toLowerCase(Locale.ROOT).contains("campfire"))
                            && obj.getWorldLocation() != null
                            && obj.getWorldLocation().distanceTo(bonfireAnchor) <= 1);
        }
        if (fire == null) {
            fire = findNearestLitFireNear(m, 10);
        }
        if (fire != null) {
            int cook = tryCookRawMeatOnFireObject(local, fire, rawName);
            if (cook > 0) {
                return cook;
            }
        } else if (bonfireAnchor != null) {
            bonfireAnchor = null;
        }
        IInventoryItem log = Inventory.getFirst(it -> it != null && it.getName() != null
                && it.getName().toLowerCase(Locale.ROOT).contains("logs"));
        IInventoryItem tinder = Inventory.getFirst("Tinderbox");
        if (log != null && tinder != null) {
            bonfireAnchor = m;
            int w = waitStarterInteractCooldown(local);
            if (w >= 0) {
                return w;
            }
            tinder.useOn(log);
            noteFiremakingAttempt();
            markStarterInteract();
            paint.setCurrentStatus("Starter: vuur voor koken");
            return afterStarterInteractDelay();
        }
        return 0;
    }

    private boolean hasLogs() {
        return Inventory.getFirst(it -> it != null && it.getName() != null
                && it.getName().toLowerCase(Locale.ROOT).contains("logs")) != null;
    }

    private boolean isStarterFmBonfireLikeAnim(int anim) {
        return anim == ANIM_FIRE || (anim >= BONFIRE_LO && anim <= BONFIRE_HI);
    }

    /**
     * Wacht één tick op firemaking/bonfire-pose alleen als er nog logs zijn of Production open is;
     * anders FM-staat afbreken (geen brandstof maar hangende anim).
     */
    private boolean holdTickForStarterFmAnimationIfRelevant(IPlayer local) {
        if (local == null) {
            return false;
        }
        if (starterBonfireFinishedNoMoreLogs) {
            starterBonfireFinishedNoMoreLogs = false;
            bonfireAnchor = null;
            firemakingAttemptStartedMs = 0L;
            return false;
        }
        if (!isStarterFmBonfireLikeAnim(local.getAnimation())) {
            return false;
        }
        if (hasLogs() || Production.isOpen()) {
            return true;
        }
        bonfireAnchor = null;
        firemakingAttemptStartedMs = 0L;
        return false;
    }

    /** Vuur / bonfire op dezelfde plane, binnen afstand (ook zonder {@code bonfireAnchor}). */
    private ITileObject findNearestLitFireNear(WorldPoint me, int maxDist) {
        if (me == null) {
            return null;
        }
        return TileObjects.getNearest(obj ->
                obj != null
                        && obj.getWorldLocation() != null
                        && obj.getWorldLocation().getPlane() == me.getPlane()
                        && obj.getName() != null
                        && (obj.getName().toLowerCase(Locale.ROOT).contains("fire")
                        || obj.getName().toLowerCase(Locale.ROOT).contains("campfire"))
                        && me.distanceTo(obj.getWorldLocation()) <= maxDist);
    }

    /**
     * Kook raw meat op een gegeven vuur (Production / anim / cooldown).
     *
     * @return &gt;0 als er gekookt of gewacht werd
     */
    private int tryCookRawMeatOnFireObject(IPlayer local, ITileObject fire, String rawName) {
        if (local == null || fire == null || rawName == null) {
            return 0;
        }
        int animPre = local.getAnimation();
        if (animPre == 896 || animPre == 897) {
            paint.setCurrentStatus("Starter: koken…");
            return gameTickDelay();
        }
        int qtyDlg = tryConfirmCookQuantityDialog();
        if (qtyDlg > 0) {
            return qtyDlg;
        }
        if (Production.isOpen()) {
            int w = waitStarterInteractCooldown(local);
            if (w >= 0) {
                return w;
            }
            Production.chooseOption(rawName);
            markStarterInteract();
            paint.setCurrentStatus("Starter: Production koken (" + rawName + ")");
            return afterStarterInteractDelay();
        }
        IInventoryItem raw = Inventory.getFirst(rawName);
        if (raw == null) {
            return 0;
        }
        int w = waitStarterInteractCooldown(local);
        if (w >= 0) {
            return w;
        }
        raw.useOn(fire);
        markStarterInteract();
        paint.setCurrentStatus("Starter: vlees koken");
        return afterStarterInteractDelay();
    }

    /**
     * Bij WC/FM: als er raw vlees is en al een vuur in de buurt, eerst koken vóór nieuw vuur / extra kap.
     * (Geen 55%-HP-eis; voorkomt patroon: kap → vuur → kap → pas koken.)
     */
    private int tryStarterOpportunisticCookOnNearbyFire(IPlayer local) {
        if (inNpcCombat()) {
            return 0;
        }
        if (!hasStarterRawMeatForCooking()) {
            return 0;
        }
        String rawName = pickStarterRawMeatName();
        if (rawName == null) {
            return 0;
        }
        WorldPoint m = local.getWorldLocation();
        if (m == null) {
            return 0;
        }
        if (holdTickForStarterFmAnimationIfRelevant(local)) {
            return gameTickDelay();
        }
        ITileObject fire = findNearestLitFireNear(m, 10);
        if (fire == null) {
            return 0;
        }
        int r = tryCookRawMeatOnFireObject(local, fire, rawName);
        if (r > 0) {
            starterRawMeatCookBatch = true;
        }
        return r;
    }

    /** Geen kill-steal: NPC niet kiezen die al door een andere speler bezet is (tenzij multi-combat). */
    private boolean isStarterNpcFreeToAttack(INPC n, IPlayer local) {
        if (n == null || local == null) {
            return false;
        }
        return NpcCombatTargetHelper.isInMultiCombatZone()
                || !NpcCombatTargetHelper.isNpcInCombatWithOther(n, local);
    }

    /** Lumbridge train: Cow en Cow calf (geen andere "cow"-NPCs in dit gebied verwacht). */
    private static boolean isLumbridgeCowTrainNpc(INPC n) {
        if (n == null || n.getName() == null) {
            return false;
        }
        String nm = n.getName().toLowerCase(Locale.ROOT);
        return "cow".equals(nm) || nm.contains("cow calf");
    }

    private String starterWcFmStatus(String action, boolean hpRegen) {
        if (hpRegen) {
            return "Starter: HP↑ " + action;
        }
        return "Starter: " + action;
    }

    /**
     * WC + FM bonfire op train-spot. {@code hpRegenForLowHp}: alleen bij geen food / HP &lt; 7 — ook als WC/FM-doelen al gehaald.
     * Bij HP-regen: eerst kap tot volle inventory, daarna pas vuur FM / bonfire (niet met 1 log beginnen).
     */
    private int starterTrainBonfireWcFm(IPlayer local, WorldPoint me, boolean hpRegenForLowHp) {
        if (!hpRegenForLowHp && inNpcCombat()) {
            paint.setCurrentStatus("Starter: eerst gevecht uit voor WC/FM…");
            long nw = System.currentTimeMillis();
            if (nw - lastWcFmBlockLogMs >= 2000) {
                lastWcFmBlockLogMs = nw;
                DebugLog.log("StarterSkill", "wc/fm: geblokkeerd — nog NPC-combat");
            }
            return gameTickDelay();
        }
        int cantLightWc = tryRelocateAfterStarterCantLightGameMessage(local);
        if (cantLightWc > 0) {
            return cantLightWc;
        }
        int fmRelocWc = checkFiremakingFailureAndMaybeRelocate(local);
        if (fmRelocWc > 0) {
            return fmRelocWc;
        }
        if (holdTickForStarterFmAnimationIfRelevant(local)) {
            return gameTickDelay();
        }
        int oppCook = tryStarterOpportunisticCookOnNearbyFire(local);
        if (oppCook > 0) {
            return oppCook;
        }
        // Als combat-doelen klaar zijn (alleen WC/FM over), kap eerst een volle inv logs.
        final boolean fillInvBeforeFm = (hpRegenForLowHp || meleeGoalsMet()) && !Inventory.isFull();
        if (Production.isOpen() && !fillInvBeforeFm) {
            IInventoryItem log = Inventory.getFirst(it -> it != null && it.getName() != null
                    && it.getName().toLowerCase(Locale.ROOT).contains("logs"));
            if (log != null) {
                int w = waitStarterInteractCooldown(local);
                if (w >= 0) {
                    return w;
                }
                Production.chooseOption(log.getName());
                markStarterInteract();
                paint.setCurrentStatus(starterWcFmStatus("bonfire FM", hpRegenForLowHp));
                return afterStarterInteractDelay();
            }
        }
        if (!fillInvBeforeFm && hasLogs() && Inventory.contains("Tinderbox") && bonfireAnchor != null) {
            ITileObject fire = TileObjects.getNearest(obj ->
                    obj != null && obj.getName() != null
                            && (obj.getName().toLowerCase(Locale.ROOT).contains("fire")
                            || obj.getName().toLowerCase(Locale.ROOT).contains("campfire"))
                            && obj.getWorldLocation() != null
                            && obj.getWorldLocation().distanceTo(bonfireAnchor) <= 1);
            if (fire != null) {
                IInventoryItem log = Inventory.getFirst(it -> it != null && it.getName() != null
                        && it.getName().toLowerCase(Locale.ROOT).contains("logs"));
                if (log != null) {
                    int w = waitStarterInteractCooldown(local);
                    if (w >= 0) {
                        return w;
                    }
                    log.useOn(fire);
                    markStarterInteract();
                    paint.setCurrentStatus(starterWcFmStatus("bonfire (logs)", hpRegenForLowHp));
                    return afterStarterInteractDelay();
                }
            }
        }
        ITileObject tree = findChoppableTreeNearTrain();
        if (tree != null) {
            if (!fillInvBeforeFm && hasLogs() && Inventory.contains("Tinderbox")) {
                WorldPoint mm = local.getWorldLocation();
                IInventoryItem log = Inventory.getFirst(it -> it != null && it.getName() != null
                        && it.getName().toLowerCase(Locale.ROOT).contains("logs"));
                IInventoryItem tinder = Inventory.getFirst("Tinderbox");
                if (log != null && tinder != null && mm != null && findNearestLitFireNear(mm, 10) == null) {
                    bonfireAnchor = mm;
                    int w = waitStarterInteractCooldown(local);
                    if (w >= 0) {
                        return w;
                    }
                    tinder.useOn(log);
                    noteFiremakingAttempt();
                    markStarterInteract();
                    paint.setCurrentStatus(starterWcFmStatus("vuur (FM)", hpRegenForLowHp));
                    return afterStarterInteractDelay();
                }
            }
            int w = waitStarterInteractCooldown(local);
            if (w >= 0) {
                return w;
            }
            tree.interact("Chop down");
            markStarterInteract();
            if (fillInvBeforeFm) {
                paint.setCurrentStatus("Starter: HP↑ WC (vul inv) — "
                        + WoodcutterConfig.getBestTreeForLevel(skill(Skill.WOODCUTTING)));
            } else {
                paint.setCurrentStatus(starterWcFmStatus("WC (" + WoodcutterConfig.getBestTreeForLevel(skill(Skill.WOODCUTTING)) + ")", hpRegenForLowHp));
            }
            DebugLog.log("StarterSkill", "WC chop: " + (tree.getName() != null ? tree.getName() : "?")
                    + " wcLvl=" + skill(Skill.WOODCUTTING) + " hpRegen=" + hpRegenForLowHp
                    + " fillInv=" + fillInvBeforeFm);
            return afterStarterInteractDelay();
        }
        paint.setCurrentStatus(hpRegenForLowHp ? "Starter: HP↑ zoek boom…" : "Starter: zoek boom WC");
        walk(destinationForTrainWalk());
        return gameTickDelay();
    }

    private int trainCombatAndSkills(IPlayer local, WorldPoint me) {
        if (!inStarterTrainZone(me)) {
            int w = deferNonCombatIfInFight("walk naar train-spot");
            if (w >= 0) {
                return w;
            }
            if (trainWalkDestination == null) {
                rollTrainWalkDestination();
            }
            walk(destinationForTrainWalk());
            return gameTickDelay();
        }
        int cookQtyDlg = tryConfirmCookQuantityDialogIfRawMeat();
        if (cookQtyDlg > 0) {
            return cookQtyDlg;
        }
        // Vóór koken/WC/eten: anders blokkeert tryCookRatOnBonfire elke tick en blijft de stijl op Aggressive.
        int styleDelay = trySwitchStarterMeleeStyle();
        if (styleDelay > 0) {
            return styleDelay;
        }
        // Starter combat: voor targetselectie altijd eerst best beschikbare gear in inventory aan.
        equipBestGearFromInventoryAfterJon();
        int hpRegenSkill = tryHpRegenViaWcFmWhenNoFood(local, me);
        if (hpRegenSkill > 0) {
            return hpRegenSkill;
        }
        int critSurvival = tryCriticalHpSurvival(local);
        if (critSurvival > 0) {
            return critSurvival;
        }
        int eatLowHp = tryEatWhenLowHp(local);
        if (eatLowHp > 0) {
            return eatLowHp;
        }
        int eatBeforeCook = tryEatCookedBeforeCookRaw(local);
        if (eatBeforeCook > 0) {
            return eatBeforeCook;
        }
        int cookDelay = tryCookRatOnBonfire(local);
        if (cookDelay > 0) {
            return cookDelay;
        }
        if (inNpcCombat() || isUnderNpcAttackNow(local)) {
            long now = System.currentTimeMillis();
            if (now - lastTrainCombatLogMs >= TRAIN_COMBAT_LOG_MS) {
                lastTrainCombatLogMs = now;
                DebugLog.log("StarterSkill", "train: in NPC-gevecht — geen bury/loot/WC tot veilig");
            }
            paint.setCurrentStatus("Starter: in gevecht…");
            return gameTickDelay();
        }
        // Extra guard: soms loopt de combat-gate 1-2 ticks achter; voorkom dan nieuw target-spam.
        if ((local.isInteracting() && local.getInteracting() instanceof INPC) || isUnderNpcAttackNow(local)) {
            paint.setCurrentStatus("Starter: gevecht actief (target vasthouden)...");
            return gameTickDelay();
        }
        int dropBurnt = dropBurntMeat(local);
        if (dropBurnt > 0) {
            return dropBurnt;
        }

        if (Inventory.contains("Bones")) {
            int bury = buryOneBone(local);
            if (bury > 0) {
                return bury;
            }
        }

        if (!Inventory.isFull()) {
            WorldPoint train = trainAreaCenter();
            ITileItem lootGround = pickNextStarterTrainBonesOrMeatLoot(local, train, STARTER_TRAIN_AREA_RADIUS);
            if (lootGround != null) {
                int w = waitStarterInteractCooldown(local);
                if (w >= 0) {
                    return w;
                }
                lootGround.pickup();
                markStarterInteract();
                String ln = lootGround.getName();
                paint.setCurrentStatus(ln != null && ln.equalsIgnoreCase("Bones")
                        ? "Starter: bones (grond)"
                        : "Starter: ruw vlees (grond)");
                return afterStarterInteractDelay();
            }
        }

        if (!meleeGoalsMet()) {
            WorldPoint train = trainAreaCenter();
            final int reach = STARTER_TRAIN_AREA_RADIUS;
            INPC target;
            if (lumbridgeSheepTrain()) {
                target = NPCs.getNearest(n -> n != null && isLumbridgeCowTrainNpc(n)
                        && !n.isDead() && n.hasAction("Attack")
                        && isStarterNpcFreeToAttack(n, local)
                        && n.getWorldLocation() != null && n.getWorldLocation().distanceTo(train) <= reach);
            } else {
                INPC rat = NPCs.getNearest(n -> n != null && n.getName() != null
                        && n.getName().toLowerCase(Locale.ROOT).contains("giant rat")
                        && !n.isDead() && n.hasAction("Attack")
                        && isStarterNpcFreeToAttack(n, local)
                        && n.getWorldLocation() != null && n.getWorldLocation().distanceTo(train) <= reach);
                INPC gob = NPCs.getNearest(n -> n != null && n.getName() != null
                        && n.getName().toLowerCase(Locale.ROOT).contains("goblin")
                        && !n.isDead() && n.hasAction("Attack")
                        && isStarterNpcFreeToAttack(n, local)
                        && n.getWorldLocation() != null && n.getWorldLocation().distanceTo(train) <= reach);
                target = rat != null ? rat : gob;
            }
            if (target != null) {
                if ((local.isInteracting() && local.getInteracting() instanceof INPC) || isUnderNpcAttackNow(local)) {
                    paint.setCurrentStatus("Starter: al in gevecht — geen nieuw target");
                    return gameTickDelay();
                }
                if (local.isMoving() || local.getAnimation() != -1) {
                    return gameTickDelay();
                }
                int w = waitStarterInteractCooldown(local);
                if (w >= 0) {
                    return w;
                }
                target.interact("Attack");
                markStarterInteract();
                paint.setCurrentStatus("Starter: combat");
                return afterStarterInteractDelay();
            }
            walk(destinationForTrainWalk());
            return gameTickDelay();
        }

        if (!wcFmGoalsMet()) {
            int wcFm = starterTrainBonfireWcFm(local, me, false);
            if (wcFm > 0) {
                return wcFm;
            }
        }

        phase = Phase.WALK_FULL_BANK_PRE_JON;
        if (lumbridgeSheepTrain()) {
            paint.setLastAntiBanAction("Starter: doelen OK → bank, daarna Jon (Claim + gear), dan bank; evt. Port Sarim voor gp");
        } else {
            paint.setLastAntiBanAction("Starter: doelen OK → Draynor bank, daarna Jon (Claim)");
        }
        return gameTickDelay();
    }

    private int loopFullBankPreJon(IPlayer local, WorldPoint me) {
        if (lumbridgeSheepTrain()) {
            if (!Bank.isOpen()) {
                if (BankHelper.tryOpenFullBank()) {
                    return gameTickDelay();
                }
                paint.setCurrentStatus("Starter: naar bank (trap) — daarna Jon (Claim)");
                BankHelper.walkToLumbridgeCastleBank();
                return gameTickDelay();
            }
        } else if (!near(me, DRAYNOR_BANK_ANCHOR, NEAR_BANK + 6)) {
            paint.setCurrentStatus("Starter: bank (voor Jon)");
            walk(DRAYNOR_BANK_ANCHOR);
            return gameTickDelay();
        }
        phase = Phase.FULL_DEPOSIT_PRE_JON;
        return gameTickDelay();
    }

    private int loopDepositAll(IPlayer local) {
        if (!Bank.isOpen()) {
            if (BankHelper.tryOpenFullBank()) return gameTickDelay();
            return gameTickDelay();
        }
        withdrawAllCoinsFromBankIfPresent();
        depositEntireInventory();
        HumanBanking.pauseBeforeClose();
        Bank.close();
        HumanBanking.pauseAfterClose();
        phase = Phase.WALK_JON;
        paint.setCurrentStatus("Starter: naar Adventurer Jon");
        paint.setLastAntiBanAction("Starter: bank klaar — naar Jon (Claim)");
        return gameTickDelay();
    }

    private int loopWalkJon(IPlayer local, WorldPoint me) {
        if (near(me, JON_SPOT, NEAR_JON)) {
            resetJonClaimDialogueState();
            phase = Phase.JON_TALK_CLAIM;
            return gameTickDelay();
        }
        paint.setCurrentStatus("Starter: naar Adventurer Jon");
        walk(JON_SPOT);
        return gameTickDelay();
    }

    private void resetJonClaimDialogueState() {
        lastJonDialogueClickMs = 0;
        jonLastNpcOpenAttemptMs = 0;
        jonDialogueWasOpen = false;
        jonNoDialogueSinceMs = 0;
    }

    /**
     * Dialoog verder klikken (Claim / Continue / …) — geen herhaalde {@code interact} op Jon tijdens chat.
     */
    private boolean advanceJonClaimDialogue() {
        return clickWidgetOptionContaining("claim")
                || clickWidgetOptionContaining("continue")
                || clickWidgetOptionContaining("click here")
                || clickWidgetOptionContaining("please")
                || clickWidgetOptionContaining("yes")
                || clickWidgetOptionContaining("ok");
    }

    /** Jon F2P: alles geclaimd — geen members; dialoog sluiten en verder (geen Claim-spam). */
    private boolean isJonF2pNoMoreRewardsDialogue() {
        return dialogueWidgetTextContains("no rewards to give you")
                || dialogueWidgetTextContains("i have no rewards")
                || (dialogueWidgetTextContains("members rewards") && dialogueWidgetTextContains("members world"));
    }

    private boolean dialogueWidgetTextContains(String needle) {
        if (needle == null || needle.isEmpty()) {
            return false;
        }
        String n = needle.toLowerCase(Locale.ROOT);
        try {
            for (int g : new int[]{143, 219, 231}) {
                for (int c = 0; c < 40; c++) {
                    IWidget root = Widgets.get(g, c);
                    if (root == null || root.isHidden()) {
                        continue;
                    }
                    for (IWidget w : flattenWidgets(root)) {
                        if (w == null || w.isHidden()) {
                            continue;
                        }
                        String t = w.getText();
                        if (t != null && t.toLowerCase(Locale.ROOT).contains(n)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void finishJonWhenNoF2pRewardsLeft(IPlayer local) {
        clickWidgetOptionContaining("click here");
        clickWidgetOptionContaining("continue");
        releaseSpace();
        phase = Phase.POST_JON_DRAYNOR_BANK;
        paint.setLastAntiBanAction("Starter: Jon — geen F2P-rewards meer; door naar bank (gear aan)");
        resetJonClaimDialogueState();
        markJonClaimCompleteInJson(local);
    }

    private int loopJon(IPlayer local) {
        INPC jon = NPCs.getNearest(n -> n != null && n.getName() != null
                && n.getName().toLowerCase(Locale.ROOT).contains("adventurer jon"));
        if (jon == null) {
            return gameTickDelay();
        }
        long now = System.currentTimeMillis();
        boolean dlg = dialogueLikelyOpen();

        if (dlg && isJonF2pNoMoreRewardsDialogue()) {
            finishJonWhenNoF2pRewardsLeft(local);
            return gameTickDelay();
        }

        if (dlg) {
            jonDialogueWasOpen = true;
            jonNoDialogueSinceMs = 0;
        } else if (jonDialogueWasOpen) {
            if (jonNoDialogueSinceMs == 0) {
                jonNoDialogueSinceMs = now;
            }
        }

        if (now - lastJonDialogueClickMs >= JON_DIALOGUE_CLICK_MS) {
            lastJonDialogueClickMs = now;
            if (dlg) {
                advanceJonClaimDialogue();
            } else if (!jonDialogueWasOpen) {
                boolean mayNpc = jonLastNpcOpenAttemptMs == 0
                        || now - jonLastNpcOpenAttemptMs >= JON_NPC_RETRY_MS;
                if (mayNpc) {
                    interactJonPreferClaim(jon);
                    jonLastNpcOpenAttemptMs = now;
                }
            }
        }

        holdSpaceThroughDialogue();

        if (jonDialogueWasOpen && !dlg && jonNoDialogueSinceMs > 0
                && now - jonNoDialogueSinceMs >= JON_DIALOGUE_DONE_QUIET_MS) {
            releaseSpace();
            phase = Phase.POST_JON_DRAYNOR_BANK;
            paint.setLastAntiBanAction("Starter: Jon afgerond");
            resetJonClaimDialogueState();
            markJonClaimCompleteInJson(local);
        }
        return gameTickDelay();
    }

    private boolean widgetGroupMaybeOpen(int group) {
        for (int c = 0; c < 30; c++) {
            IWidget w = Widgets.get(group, c);
            if (w != null && !w.isHidden()) {
                return true;
            }
        }
        return false;
    }

    private void holdSpaceThroughDialogue() {
        try {
            net.storm.sdk.input.Keyboard.pressed(KeyEvent.VK_SPACE);
            spaceHeld = true;
        } catch (Throwable ignored) {
        }
    }

    private void releaseSpace() {
        if (!spaceHeld) return;
        try {
            net.storm.sdk.input.Keyboard.released(KeyEvent.VK_SPACE);
        } catch (Throwable ignored) {
        }
        spaceHeld = false;
    }

    private boolean dialogueLikelyOpen() {
        try {
            IWidget a = Widgets.get(219, 1);
            if (a != null && !a.isHidden()) return true;
            IWidget b = Widgets.get(231, 5);
            if (b != null && !b.isHidden()) return true;
            return widgetGroupMaybeOpen(143);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Rechterklik-menu: altijd Claim i.p.v. Talk-to (Adventure Paths-beloningen). */
    private void interactJonPreferClaim(INPC jon) {
        if (jon == null) return;
        try {
            String[] actions = jon.getActions();
            if (actions != null) {
                for (String a : actions) {
                    if (a != null && a.toLowerCase(Locale.ROOT).contains("claim")) {
                        jon.interact(a);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (jon.hasAction("Talk-to")) {
            jon.interact("Talk-to");
        }
    }

    /** @return {@code true} als er op een passende widget is geklikt. */
    private boolean clickWidgetOptionContaining(String needle) {
        if (needle == null || needle.isEmpty()) {
            return false;
        }
        try {
            for (int g : new int[]{143, 219, 231}) {
                for (int c = 0; c < 40; c++) {
                    IWidget root = Widgets.get(g, c);
                    if (root == null || root.isHidden()) continue;
                    for (IWidget w : flattenWidgets(root)) {
                        if (w == null || w.isHidden()) continue;
                        String t = w.getText();
                        if (t != null && t.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) {
                            w.interact(0);
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private List<IWidget> flattenWidgets(IWidget root) {
        List<IWidget> out = new ArrayList<>();
        if (root == null) return out;
        Deque<IWidget> dq = new ArrayDeque<>();
        dq.add(root);
        while (!dq.isEmpty()) {
            IWidget w = dq.poll();
            if (w == null) continue;
            out.add(w);
            try {
                if (w.getChildren() != null) {
                    for (IWidget ch : w.getChildren()) {
                        if (ch != null) dq.add(ch);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private int loopPostJonBank(IPlayer local, WorldPoint me) {
        releaseSpace();
        equipBestGearFromInventoryAfterJon();
        if (lumbridgeSheepTrain()) {
            if (!Bank.isOpen()) {
                if (BankHelper.tryOpenFullBank()) {
                    return gameTickDelay();
                }
                paint.setCurrentStatus("Starter: bank (na Jon, Lumbridge)");
                BankHelper.walkToLumbridgeCastleBank();
                return gameTickDelay();
            }
        } else {
            if (!near(me, DRAYNOR_BANK_ANCHOR, NEAR_BANK + 8)) {
                walk(DRAYNOR_BANK_ANCHOR);
                return gameTickDelay();
            }
            if (!Bank.isOpen()) {
                if (BankHelper.tryOpenFullBank()) {
                    return gameTickDelay();
                }
                return gameTickDelay();
            }
        }
        withdrawAllCoinsFromBankIfPresent();
        withdrawAndEquipWarriorMvp();
        depositEntireInventory();
        HumanBanking.pauseBetweenActions();
        if (Bank.contains("Coins")) {
            Bank.withdrawAll("Coins");
            HumanBanking.pauseBetweenActions();
        }
        HumanBanking.pauseBeforeClose();
        Bank.close();
        HumanBanking.pauseAfterClose();
        phase = Phase.POST_JON_EQUIP_AND_COINS;
        return gameTickDelay();
    }

    private void withdrawAndEquipWarriorMvp() {
        withdrawBestWarriorArmorFromBank();
        withdrawBestShieldFromBank();
        HumanBanking.pauseAfterWithdrawBatch();
        wearBestWarriorArmorFromInventory();
        wearBestShieldFromInventory();
        // Zelfde tier-upgrade als eerste bank: alleen strikt beter uit bank + wield als beter dan Jon-equip.
        withdrawStarterTieredWeaponUpgradeFromBank();
        wieldStarterTierWeaponIfInInventory(pickStrictlyBetterUsableWeaponInInventoryThanEquipped());
    }

    private int loopCoinsBranch(IPlayer local, WorldPoint me) {
        int coins = coinInv();
        if (coins >= COIN_HANDOFF) {
            phase = Phase.HANDOFF_IMPS;
            return gameTickDelay();
        }
        phase = Phase.FARM_GOBLINS_FOR_30GP;
        trainWalkDestination = null;
        rollTrainWalkDestination();
        if (lumbridgeSheepTrain()) {
            paint.setLastAntiBanAction("Starter: na Jon + bank → Port Sarim (30 gp voor imps)");
        }
        return gameTickDelay();
    }

    /** Som van alle coin-stacks (niet alleen {@link Inventory#getFirst}). */
    private int coinInv() {
        int sum = 0;
        try {
            for (IInventoryItem it : Inventory.getAll()) {
                if (it == null || it.getName() == null) {
                    continue;
                }
                if ("Coins".equalsIgnoreCase(Text.removeTags(it.getName()).trim())) {
                    sum += it.getQuantity();
                }
            }
        } catch (Throwable ignored) {
        }
        return sum;
    }

    /** Grond-loot in een zone; bij {@code lootOnlyOwn} alleen eigen drops via {@code TileItems.getAllMine()}. */
    private ITileItem nearestStarterGroundLoot(WorldPoint anchor, int maxDist, Predicate<ITileItem> nameMatch) {
        if (anchor == null) return null;
        Predicate<ITileItem> inZone = ti -> ti != null && ti.getWorldLocation() != null
                && anchor.distanceTo(ti.getWorldLocation()) <= maxDist
                && nameMatch.test(ti);
        if (config.lootOnlyOwn()) {
            return TileItems.getAllMine().stream()
                    .filter(inZone::test)
                    .min(Comparator.comparingInt(i -> i.getWorldLocation().distanceTo(anchor)))
                    .orElse(null);
        }
        return TileItems.getNearest(inZone::test);
    }

    private static boolean isStarterTrainGroundMeatName(String name) {
        if (name == null) {
            return false;
        }
        return name.equalsIgnoreCase("Raw rat meat")
                || name.equalsIgnoreCase("Rat meat")
                || name.equalsIgnoreCase("Raw beef");
    }

    /** Eerste grond-item op exact deze tegel (zelfde {@link WorldPoint}) dat aan {@code typeMatch} voldoet. */
    private ITileItem firstStarterGroundLootOnTile(WorldPoint tile, Predicate<ITileItem> typeMatch) {
        if (tile == null) {
            return null;
        }
        Predicate<ITileItem> onTile = ti -> ti != null
                && ti.getWorldLocation() != null
                && tile.equals(ti.getWorldLocation())
                && typeMatch.test(ti);
        if (config.lootOnlyOwn()) {
            return TileItems.getAllMine().stream()
                    .filter(onTile)
                    .findFirst()
                    .orElse(null);
        }
        return TileItems.getNearest(onTile::test);
    }

    /**
     * Kiest één grond-item: liever bones + ruw vlees op dezelfde tegel (dichtst bij speler) vóór
     * bones op tile A en vlees op tile B (geen heen-en-weer).
     */
    private ITileItem pickNextStarterTrainBonesOrMeatLoot(IPlayer local, WorldPoint anchor, int maxDist) {
        WorldPoint me = local.getWorldLocation();
        if (me == null || anchor == null) {
            return null;
        }
        Predicate<ITileItem> bonePred = ti -> ti != null && ti.getName() != null
                && ti.getName().equalsIgnoreCase("Bones");
        Predicate<ITileItem> meatPred = ti -> ti != null && ti.getName() != null
                && isStarterTrainGroundMeatName(ti.getName());

        ITileItem bonesNear = nearestStarterGroundLoot(anchor, maxDist, bonePred);
        boolean canMeat = countStarterRawMeatPieces() < STARTER_RAW_MEAT_PICKUP_CAP;
        ITileItem meatNear = canMeat ? nearestStarterGroundLoot(anchor, maxDist, meatPred) : null;

        ITileItem meatOnBoneTile = (bonesNear != null && canMeat)
                ? firstStarterGroundLootOnTile(bonesNear.getWorldLocation(), meatPred)
                : null;
        ITileItem bonesOnMeatTile = (meatNear != null)
                ? firstStarterGroundLootOnTile(meatNear.getWorldLocation(), bonePred)
                : null;

        WorldPoint comboWpA = (bonesNear != null && meatOnBoneTile != null) ? bonesNear.getWorldLocation() : null;
        WorldPoint comboWpB = (meatNear != null && bonesOnMeatTile != null) ? meatNear.getWorldLocation() : null;

        if (comboWpA != null && comboWpB != null) {
            int da = me.distanceTo(comboWpA);
            int db = me.distanceTo(comboWpB);
            if (da < db) {
                return canMeat ? meatOnBoneTile : bonesNear;
            }
            if (db < da) {
                return canMeat ? meatNear : bonesOnMeatTile;
            }
            return canMeat ? meatOnBoneTile : bonesNear;
        }
        if (comboWpA != null) {
            return canMeat ? meatOnBoneTile : bonesNear;
        }
        if (comboWpB != null) {
            return canMeat ? meatNear : bonesOnMeatTile;
        }

        if (bonesNear != null && meatNear != null) {
            int dbn = me.distanceTo(bonesNear.getWorldLocation());
            int dmn = me.distanceTo(meatNear.getWorldLocation());
            if (dbn < dmn) {
                return bonesNear;
            }
            if (dmn < dbn) {
                return meatNear;
            }
            return canMeat ? meatNear : bonesNear;
        }
        if (bonesNear != null) {
            return bonesNear;
        }
        return meatNear;
    }

    private int farmGoblins(IPlayer local, WorldPoint me) {
        if (inNpcCombat()) {
            logDeferThrottled("farm goblins (in gevecht)");
            paint.setCurrentStatus("Starter: goblin-gevecht…");
            return gameTickDelay();
        }
        int goblinStyle = trySwitchStarterMeleeStyle();
        if (goblinStyle > 0) {
            return goblinStyle;
        }
        if (coinInv() < COIN_HANDOFF) {
            ITileItem gp = nearestStarterGroundLoot(goblinFarmCenter(), 14,
                    ti -> ti.getName() != null && ti.getName().equalsIgnoreCase("Coins"));
            if (gp != null && !Inventory.isFull()) {
                int w = waitStarterInteractCooldown(local);
                if (w >= 0) {
                    return w;
                }
                gp.pickup();
                markStarterInteract();
                paint.setCurrentStatus("Starter: coins oprapen");
                return afterStarterInteractDelay();
            }
        }
        if (coinInv() >= COIN_HANDOFF) {
            phase = Phase.HANDOFF_IMPS;
            return gameTickDelay();
        }
        if (!inGoblinFarmZone(me)) {
            // Lumbridge: niet jitteren rond anker vanaf ver weg (path kan langs docks/verkeerde kust); eerst naar vaste goblin-tegel.
            if (lumbridgeSheepTrain()) {
                trainWalkDestination = null;
                walk(PORT_SARIM_GOBLIN_ANCHOR);
                return gameTickDelay();
            }
            if (trainWalkDestination == null) {
                rollTrainWalkDestination();
            }
            walk(destinationForTrainWalk());
            return gameTickDelay();
        }
        WorldPoint farm = goblinFarmCenter();
        INPC gob = NPCs.getNearest(n -> n != null && n.getName() != null
                && n.getName().toLowerCase(Locale.ROOT).contains("goblin")
                && !n.isDead() && n.hasAction("Attack")
                && isStarterNpcFreeToAttack(n, local)
                && n.getWorldLocation() != null && n.getWorldLocation().distanceTo(farm) <= 14);
        if (gob != null) {
            if (local.isMoving() || local.getAnimation() != -1) {
                return gameTickDelay();
            }
            int w = waitStarterInteractCooldown(local);
            if (w >= 0) {
                return w;
            }
            gob.interact("Attack");
            markStarterInteract();
            paint.setCurrentStatus("Starter: goblins voor 30 gp");
            return afterStarterInteractDelay();
        }
        walk(destinationForTrainWalk());
        return gameTickDelay();
    }

    private int handoffImps() {
        releaseSpace();
        if (onHandoffToImps != null) {
            onHandoffToImps.run();
        }
        paint.setLastAntiBanAction("Starter: door naar Imps (30+ gp)");
        return gameTickDelay();
    }

    public int loop() {
        try {
            IPlayer local = Players.getLocal();
            if (local == null) {
                DebugLog.log("StarterSkill", "loop: local=null");
                return 1000;
            }
            WorldPoint me = local.getWorldLocation();
            if (!config.botEnabled()) {
                releaseSpace();
                return 1000;
            }

            ensureStarterAccountHydrated(local);
            logPhaseIfChanged();
            syncStarterOverlay();

            if (phaseMustDeferBankWalkJon(phase)) {
                int waitCombat = deferNonCombatIfInFight(phase.name());
                if (waitCombat >= 0) {
                    return waitCombat;
                }
            }

            switch (phase) {
                case WALK_DRAYNOR_BANK:
                    return loopInitialBank(local, me);
                case WALK_TRAIN_SPOT:
                    return loopWalkTrain(local, me);
                case TRAIN_COMBAT_AND_SKILLS:
                    return trainCombatAndSkills(local, me);
                case WALK_FULL_BANK_PRE_JON:
                    return loopFullBankPreJon(local, me);
                case FULL_DEPOSIT_PRE_JON:
                    return loopDepositAll(local);
                case WALK_JON:
                    return loopWalkJon(local, me);
                case JON_TALK_CLAIM:
                    return loopJon(local);
                case POST_JON_DRAYNOR_BANK:
                    return loopPostJonBank(local, me);
                case POST_JON_EQUIP_AND_COINS:
                    return loopCoinsBranch(local, me);
                case FARM_GOBLINS_FOR_30GP:
                    return farmGoblins(local, me);
                case HANDOFF_IMPS:
                    return handoffImps();
                default:
                    return gameTickDelay();
            }
        } catch (Exception e) {
            DebugLog.log("StarterSkill", "loop: " + e.getMessage());
            return 1500;
        }
    }
}
