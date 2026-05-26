package com.combatbot;

import net.runelite.api.coords.WorldPoint;
import net.storm.api.domain.actors.INPC;
import net.storm.api.domain.actors.IPlayer;
import net.storm.sdk.entities.NPCs;
import net.storm.sdk.entities.Players;
import net.storm.sdk.entities.TileObjects;
import net.storm.sdk.game.Vars;
import net.storm.sdk.input.Keyboard;
import net.storm.sdk.items.Inventory;
import net.storm.sdk.magic.Magic;
import net.storm.api.widgets.Tab;
import net.storm.api.magic.SpellBook;
import net.storm.sdk.widgets.Dialog;
import net.storm.sdk.widgets.Tabs;
import net.storm.sdk.widgets.Widgets;
import net.storm.api.domain.widgets.IWidget;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Lichte Tutorial Island flow met veilige stuck-detectie.
 */
public final class TutorialModeHandler {
    private static final int TUTORIAL_PROGRESS_VARP = 281;
    private static final int TUTORIAL_DONE_VALUE = 1000;
    private static final long STEP_STUCK_MS = 120_000L;
    /** Tutorial hint-tekst (linksboven) — iface 263, alle zichtbare children worden plat gelezen. */
    private static final int HINT_GROUP = 263;
    /** Smithing: Bronze dagger — iface 312,9 */
    private static final int SMITH_BRONZE_DAGGER_GROUP = 312;
    private static final int SMITH_BRONZE_DAGGER_CHILD = 9;
    /** Display name — dump: iface 558 (Set name, beschikbaarheidstekst). */
    private static final int DISPLAY_NAME_GROUP = 558;
    /** Character creator — dump: iface 679 (Select, Confirm, pronouns). */
    private static final int CHARACTER_CREATOR_GROUP = 679;
    private static final WorldPoint GUIDE_ANCHOR = new WorldPoint(3098, 3107, 0);
    private static final WorldPoint SURVIVAL_ANCHOR = new WorldPoint(3103, 3096, 0);
    private static final WorldPoint CHEF_ANCHOR = new WorldPoint(3075, 3090, 0);
    private static final WorldPoint QUEST_ANCHOR = new WorldPoint(3086, 3126, 0);
    private static final WorldPoint MINING_ANCHOR = new WorldPoint(3076, 9504, 0);
    private static final WorldPoint COMBAT_ANCHOR = new WorldPoint(3100, 9506, 0);
    private static final WorldPoint FINANCE_ANCHOR = new WorldPoint(3124, 3124, 0);
    private static final WorldPoint PRAYER_ANCHOR = new WorldPoint(3124, 3107, 0);
    private static final WorldPoint MAGIC_ANCHOR = new WorldPoint(3141, 3089, 0);
    /** Gielinor / RuneScape Guide — meerdere cache-IDs na tut-rework (ML-log + wiki). */
    private static final int[] GIELINOR_GUIDE_NPC_IDS = {3308, 3312, 3313};
    private static final List<String> INSTRUCTOR_NAMES = Arrays.asList(
            "Gielinor Guide", "Survival Expert", "Combat Instructor", "Master Chef", "Quest Guide",
            "Mining Instructor", "Financial Advisor", "Account Guide", "Brother Brace", "Magic Instructor",
            "RuneScape Guide"
    );

    private final CombatBotPaint paint;
    private final CombatBotConfig config;
    private final Random random = new Random();
    private String lastStepKey = "";
    private long lastStepSinceMs = 0L;
    private long nextActionAtMs = 0L;
    private long nextNameTypeAtMs = 0L;
    private String pendingDisplayName = "";
    private boolean creatorRandomizedThisOpen = false;
    private int creatorRandomClicksLeft = 0;
    /** Zijpaneel: Display → Resizable (Modern) eenmalig tijdens vroege tut. */
    private boolean tutResizableLayoutDone = false;
    private boolean tutDisplayDropdownMaybeOpen = false;
    private boolean stepStuck;
    private String stuckReason = "";
    /** Tussen dialoog-kliks: menselijk tempo i.p.v. machinegun-space. */
    private long nextDialogClickAtMs = 0L;
    private String lastHintSnapshot = "";
    /** Display name: focus invoerveld (ML: Enter name vóór typen). */
    private long lastEnterNameFieldClickMs = 0L;
    /** Namen uit config-pool die deze sessie al 'not available' gaven. */
    private final Set<String> rejectedDisplayPoolNames = new HashSet<>();
    /** Laatste naam waarvoor we "Look up name" hebben geklikt — alleen dan is "not available" geldig voor {@link #pendingDisplayName}. */
    private String lastDisplayNameLookupAttempt = "";

    public TutorialModeHandler(CombatBotPaint paint, CombatBotConfig config) {
        this.paint = paint;
        this.config = config;
    }

    public int loop() {
        stepStuck = false;
        stuckReason = "";

        int onboardingDelay = handleOnboardingInterfaces();
        if (onboardingDelay > 0) {
            return onboardingDelay;
        }

        IPlayer lp = Players.getLocal();
        if (lp == null) {
            return 500;
        }
        if (!isOnTutorialIsland(lp) || isTutorialFinishedByVarp()) {
            paint.setCurrentStatus("Tut: voltooid");
            resetStepTracker();
            return 600;
        }

        int progress = readTutorialProgressVarp();
        String locationPhase = resolveLocationPhase(lp.getWorldLocation());
        String stepKey = "varp_" + progress + "_" + locationPhase;
        trackStep(stepKey);
        String hint = readTutorialHintRaw();
        if (!hint.equals(lastHintSnapshot)) {
            lastHintSnapshot = hint;
            maybeBumpReadPauseForNewHint();
        }
        paint.setCurrentStatus("Tut mode: stap " + progress + " (" + locationPhase + ")");

        Integer dialogDelay = handleTutorialDialogs();
        if (dialogDelay != null) {
            return dialogDelay;
        }
        if (System.currentTimeMillis() < nextActionAtMs) {
            return humanPause(320, 700);
        }

        if (tryEarlyGielinorGuideInteraction(progress, locationPhase, lp)) {
            nextActionAtMs = System.currentTimeMillis() + humanPause(550, 1200);
            return humanPause(600, 1100);
        }

        if (progress < 20 && tryEnsureResizableClientLayoutEarlyTut()) {
            nextActionAtMs = System.currentTimeMillis() + 700L;
            return 900;
        }

        String expectedInstructor = expectedInstructorFor(progress, locationPhase);
        if (tryProgressAction(progress, locationPhase)) {
            nextActionAtMs = System.currentTimeMillis() + humanPause(650, 1400);
            return humanPause(700, 1200);
        }
        if (talkToInstructor(expectedInstructor)) {
            nextActionAtMs = System.currentTimeMillis() + humanPause(750, 1600);
            return humanPause(800, 1300);
        }
        WorldPoint anchor = expectedAnchorFor(progress, locationPhase);
        if (anchor != null && lp.getWorldLocation() != null && lp.getWorldLocation().distanceTo(anchor) > 10) {
            MovementHelper.walkTo(anchor);
            nextActionAtMs = System.currentTimeMillis() + humanPause(700, 1300);
            return humanPause(750, 1200);
        }
        if (openNearbyDoorOrGate()) {
            nextActionAtMs = System.currentTimeMillis() + humanPause(650, 1200);
            return humanPause(700, 1100);
        }
        return humanPause(650, 1100);
    }

    private int handleOnboardingInterfaces() {
        if (isDisplayNamePanelVisible()) {
            paint.setCurrentStatus("Tut: display name instellen");
            return handleDisplayNameUi() ? 900 : 450;
        }
        if (isCharacterCreatorPanelVisible()) {
            paint.setCurrentStatus("Tut: character creator");
            if (handleCharacterCreatorUi()) {
                return 900;
            }
            return 450;
        }
        if (isWidgetTextVisible("how familiar are you with old school runescape")
                || isWidgetTextVisible("past experience")
                || isWidgetTextVisible("returning player")) {
            paint.setCurrentStatus("Tut: ervaring / familiarity");
            creatorRandomizedThisOpen = false;
            creatorRandomClicksLeft = 0;
            if (clickWidgetContaining("returning player")
                    || clickWidgetContaining("played in the past")
                    || clickWidgetContaining("experienced player")
                    || clickWidgetContaining("brand new")) {
                return 900;
            }
            return 450;
        }
        return -1;
    }

    private boolean isDisplayNamePanelVisible() {
        if (isWidgetTextVisible("set display name") || isWidgetTextVisible("look up name")) {
            return true;
        }
        for (int c = 0; c < 30; c++) {
            try {
                IWidget w = Widgets.get(DISPLAY_NAME_GROUP, c);
                if (w != null && !w.isHidden()) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private boolean isCharacterCreatorPanelVisible() {
        if (isWidgetTextVisible("character creator")) {
            return true;
        }
        for (int c = 0; c < 80; c++) {
            try {
                IWidget w = Widgets.get(CHARACTER_CREATOR_GROUP, c);
                if (w != null && !w.isHidden()) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private boolean handleCharacterCreatorUi() {
        if (!creatorRandomizedThisOpen) {
            creatorRandomizedThisOpen = true;
            creatorRandomClicksLeft = 2 + random.nextInt(3); // 2-4 random uiterlijk-kliks
        }
        if (creatorRandomClicksLeft > 0) {
            IWidget sel = pickRandomCharacterCreatorSelect();
            if (sel != null) {
                try {
                    sel.interact("Select");
                    creatorRandomClicksLeft--;
                    return true;
                } catch (Throwable ignored) {
                }
            }
            IWidget arrow = pickRandomCreatorArrowWidget();
            if (arrow != null) {
                try {
                    arrow.interact(0);
                    creatorRandomClicksLeft--;
                    return true;
                } catch (Throwable ignored) {
                }
            } else {
                creatorRandomClicksLeft = 0;
            }
        }
        return clickWidgetWithAction("Confirm") || clickWidgetContaining("confirm");
    }

    /** Nieuwe creator (679): vele knoppen met action Select. */
    private IWidget pickRandomCharacterCreatorSelect() {
        java.util.ArrayList<IWidget> candidates = new java.util.ArrayList<>();
        for (int c = 0; c < 80; c++) {
            IWidget root = Widgets.get(CHARACTER_CREATOR_GROUP, c);
            if (root == null || root.isHidden()) {
                continue;
            }
            for (IWidget w : flattenWidgets(root)) {
                if (w == null || w.isHidden()) {
                    continue;
                }
                try {
                    if (!w.hasAction("Select")) {
                        continue;
                    }
                } catch (Throwable ignored) {
                    continue;
                }
                String t = w.getText();
                if (t != null && t.trim().equalsIgnoreCase("Confirm")) {
                    continue;
                }
                candidates.add(w);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    private IWidget pickRandomCreatorArrowWidget() {
        java.util.ArrayList<IWidget> candidates = new java.util.ArrayList<>();
        for (int g = 0; g <= 800; g++) {
            for (int c = 0; c < 40; c++) {
                IWidget root = Widgets.get(g, c);
                if (root == null || root.isHidden()) continue;
                for (IWidget w : flattenWidgets(root)) {
                    if (w == null || w.isHidden()) continue;
                    String t = w.getText();
                    String lower = t == null ? "" : t.trim().toLowerCase(Locale.ROOT);
                    // In creator zijn pijlen vaak textloos of met < / >
                    boolean arrowLike = lower.isEmpty() || "<".equals(lower) || ">".equals(lower);
                    if (!arrowLike) continue;
                    try {
                        if (w.hasAction("Select") || w.hasAction("Choose") || w.hasAction("Toggle") || w.hasAction("Continue")) {
                            candidates.add(w);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(random.nextInt(candidates.size()));
    }

    private boolean handleDisplayNameUi() {
        if (isWidgetTextVisible("requesting") || isWidgetTextVisible("please wait")) {
            return false;
        }
        if (isWidgetTextVisible("is available")) {
            lastDisplayNameLookupAttempt = "";
            return clickWidgetWithAction("Set name") || clickWidgetContaining("set name");
        }
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastEnterNameFieldClickMs >= 2200L
                && (isWidgetTextVisible("please look up") || isWidgetTextVisible("enter name"))) {
            if (clickWidgetWithAction("Enter name")) {
                lastEnterNameFieldClickMs = nowMs;
                return true;
            }
        }
        boolean unavailable = isWidgetTextVisible("not available");
        boolean lookupFailedForPending = unavailable && !pendingDisplayName.isBlank()
                && pendingDisplayName.equals(lastDisplayNameLookupAttempt);
        if (pendingDisplayName.isBlank() || lookupFailedForPending) {
            if (lookupFailedForPending) {
                rejectedDisplayPoolNames.add(pendingDisplayName);
            }
            pendingDisplayName = pickDisplayName();
            lastDisplayNameLookupAttempt = "";
            nextNameTypeAtMs = 0L;
        }
        long now = System.currentTimeMillis();
        if (now >= nextNameTypeAtMs) {
            Keyboard.type(pendingDisplayName);
            nextNameTypeAtMs = now + 1500L;
            return true;
        }
        if (clickWidgetWithAction("Look up name") || clickWidgetContaining("look up name")) {
            lastDisplayNameLookupAttempt = pendingDisplayName;
            return true;
        }
        return clickWidgetWithAction("Enter name");
    }

    private List<String> parseDisplayNamePool(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split("[,\\r\\n]+")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private String pickDisplayName() {
        List<String> pool = parseDisplayNamePool(config.tutDisplayNamePool());
        List<String> candidates = new ArrayList<>();
        for (String n : pool) {
            if (!rejectedDisplayPoolNames.contains(n)) {
                candidates.add(n);
            }
        }
        if (!candidates.isEmpty()) {
            return candidates.get(random.nextInt(candidates.size()));
        }
        return generateDisplayName();
    }

    private String generateDisplayName() {
        String alpha = "abcdefghijklmnopqrstuvwxyz";
        int len = 5 + random.nextInt(4);
        StringBuilder sb = new StringBuilder(len + 3);
        for (int i = 0; i < len; i++) {
            sb.append(alpha.charAt(random.nextInt(alpha.length())));
        }
        sb.append(100 + random.nextInt(900));
        return sb.toString();
    }

    private boolean tryProgressAction(int progress, String locationPhase) {
        if (tryInterfacePatternActions()) {
            return true;
        }
        if ("guide".equals(locationPhase)) {
            return openNearbyDoorOrGate();
        }
        if ("survival".equals(locationPhase)) {
            return doSurvivalActions();
        }
        if ("chef".equals(locationPhase) || "quest".equals(locationPhase)) {
            return doChefActions();
        }
        if ("mining".equals(locationPhase)) {
            return doMiningActions();
        }
        if ("combat".equals(locationPhase)) {
            return doCombatActions();
        }
        if ("finance".equals(locationPhase)) {
            return doFinanceActions();
        }
        if ("prayer".equals(locationPhase)) {
            return doPrayerActions();
        }
        if ("magic".equals(locationPhase)) {
            return doMagicActions();
        }
        if (progress < 10) {
            return openNearbyDoorOrGate();
        }
        if (progress < 20) {
            return doSurvivalActions();
        }
        if (progress < 30) {
            return doChefActions();
        }
        if (progress < 40) {
            return openNearbyDoorOrGate();
        }
        if (progress < 50) {
            return doMiningActions();
        }
        if (progress < 60) {
            return doCombatActions();
        }
        if (progress < 70) {
            return doFinanceActions();
        }
        if (progress < 80) {
            return doPrayerActions();
        }
        return doMagicActions();
    }

    private boolean doSurvivalActions() {
        if (!Inventory.contains("Net")) {
            INPC spot = NPCs.getNearest(n -> n != null && n.getName() != null
                    && n.getName().toLowerCase(Locale.ROOT).contains("fishing spot"));
            if (spot != null && spot.hasAction("Net")) {
                spot.interact("Net");
                return true;
            }
        }
        if (Inventory.contains("Raw shrimps")) {
            var fire = TileObjects.getNearest(o -> o != null && o.getName() != null
                    && (o.getName().equalsIgnoreCase("Fire") || o.getName().equalsIgnoreCase("Range")));
            if (fire != null) {
                if (fire.hasAction("Cook")) fire.interact("Cook");
                else if (fire.hasAction("Cook at")) fire.interact("Cook at");
                else fire.interact("Use");
                return true;
            }
        }
        return openNearbyDoorOrGate();
    }

    private boolean doChefActions() {
        String hint = readTutorialHintLower();
        if (Inventory.contains("Bread dough") || hint.contains("bake your dough") || hint.contains("bake some bread")) {
            var range = TileObjects.getNearest(o -> o != null && o.getName() != null && o.getName().equalsIgnoreCase("Range"));
            if (range != null) {
                if (range.hasAction("Cook")) range.interact("Cook");
                else if (range.hasAction("Cook at")) range.interact("Cook at");
                else range.interact("Use");
                return true;
            }
        }
        if (Inventory.contains("Raw shrimps")) {
            var range = TileObjects.getNearest(o -> o != null && o.getName() != null && o.getName().equalsIgnoreCase("Range"));
            if (range != null) {
                if (range.hasAction("Cook")) range.interact("Cook");
                else if (range.hasAction("Cook at")) range.interact("Cook at");
                else range.interact("Use");
                return true;
            }
        }
        return openNearbyDoorOrGate();
    }

    private boolean doMiningActions() {
        if (trySmithBronzeDaggerFromInterface()) {
            return true;
        }
        if (tryMineRockFromContext()) {
            return true;
        }
        if ((Inventory.contains("Tin ore") || Inventory.contains("Copper ore")) && !Inventory.contains("Bronze bar")) {
            var furnace = TileObjects.getNearest(o -> o != null && o.getName() != null && o.getName().equalsIgnoreCase("Furnace"));
            if (furnace != null) {
                if (furnace.hasAction("Smelt")) furnace.interact("Smelt");
                else furnace.interact("Use");
                return true;
            }
        }
        if (Inventory.contains("Bronze bar")) {
            var anvil = TileObjects.getNearest(o -> o != null && o.getName() != null && o.getName().equalsIgnoreCase("Anvil"));
            if (anvil != null) {
                if (anvil.hasAction("Smith")) anvil.interact("Smith");
                else anvil.interact("Use");
                return true;
            }
        }
        return openNearbyDoorOrGate();
    }

    private boolean doCombatActions() {
        String hint = readTutorialHintLower();
        boolean rangedPhase = hint.contains("shortbow") || hint.contains("rat ranging")
                || hint.contains("bow and arrows") || (Inventory.contains("Shortbow") && Inventory.contains("Bronze arrow"));
        if (rangedPhase) {
            if (equipIfPresent("Shortbow")) return true;
            if (equipIfPresent("Bronze arrow")) return true;
        } else {
            if (equipIfPresent("Bronze dagger")) return true;
            if (equipIfPresent("Bronze sword")) return true;
            if (equipIfPresent("Wooden shield")) return true;
        }
        if (hint.contains("combat interface") || hint.contains("attack a rat") || hint.contains("attack style")) {
            try {
                Tabs.open(Tab.COMBAT);
            } catch (Throwable ignored) {
            }
        }
        INPC rat = NPCs.getNearest(n -> n != null && n.getName() != null && n.getName().equalsIgnoreCase("Giant rat"));
        if (rat != null && rat.hasAction("Attack")) {
            rat.interact("Attack");
            return true;
        }
        return openNearbyDoorOrGate();
    }

    private boolean doFinanceActions() {
        var bankBooth = TileObjects.getNearest(o -> o != null && o.getName() != null && o.getName().equalsIgnoreCase("Bank booth"));
        if (bankBooth != null) {
            if (bankBooth.hasAction("Bank")) bankBooth.interact("Bank");
            else bankBooth.interact("Use");
            return true;
        }
        var pollBooth = TileObjects.getNearest(o -> o != null && o.getName() != null && o.getName().equalsIgnoreCase("Poll booth"));
        if (pollBooth != null) {
            pollBooth.interact("Use");
            return true;
        }
        return openNearbyDoorOrGate();
    }

    private boolean doPrayerActions() {
        if (Inventory.contains("Bones")) {
            return inventoryInteract("Bones", "Bury");
        }
        return openNearbyDoorOrGate();
    }

    private boolean doMagicActions() {
        String hint = readTutorialHintLower();
        if (hint.contains("home teleport") || hint.contains("to the mainland") || hint.contains("nearly finished")) {
            if (tryCastHomeTeleport()) {
                return true;
            }
        }
        boolean windTutorial = hint.contains("wind strike") || hint.contains("magic casting")
                || hint.contains("cast your first spell") || hint.contains("first spell");
        INPC chicken = NPCs.getNearest(n -> n != null && n.getName() != null && n.getName().equalsIgnoreCase("Chicken"));
        if (chicken != null && (windTutorial || hint.contains("chicken"))) {
            try {
                Tabs.open(Tab.MAGIC);
            } catch (Throwable ignored) {
            }
            try {
                Magic.cast(net.storm.api.magic.SpellBook.Standard.WIND_STRIKE, chicken);
                return true;
            } catch (Throwable ignored) {
                if (chicken.hasAction("Attack")) {
                    chicken.interact("Attack");
                    return true;
                }
            }
        }
        return openNearbyDoorOrGate();
    }

    private boolean interactNpcTalk(INPC npc) {
        if (npc == null) {
            return false;
        }
        try {
            if (npc.hasAction("Talk-to")) {
                npc.interact("Talk-to");
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (npc.hasAction("Talk")) {
                npc.interact("Talk");
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            npc.interact(0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean talkToInstructor(String expectedName) {
        if (expectedName != null && expectedName.toLowerCase(Locale.ROOT).contains("gielinor")) {
            try {
                for (int gid : GIELINOR_GUIDE_NPC_IDS) {
                    INPC byId = NPCs.getNearest(n -> n != null && n.getId() == gid);
                    if (byId != null && interactNpcTalk(byId)) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        INPC instructor = NPCs.getNearest(n -> {
            if (n == null || n.getName() == null) return false;
            String lower = n.getName().toLowerCase(Locale.ROOT);
            if (expectedName != null && !expectedName.isBlank()) {
                String en = expectedName.toLowerCase(Locale.ROOT);
                if (lower.contains(en)) {
                    return true;
                }
                if ((en.contains("financial") || en.contains("advisor")) && lower.contains("account guide")) {
                    return true;
                }
            }
            for (String candidate : INSTRUCTOR_NAMES) {
                if (lower.contains(candidate.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        });
        return instructor != null && interactNpcTalk(instructor);
    }

    private boolean openNearbyDoorOrGate() {
        var barrier = TileObjects.getNearest(o -> o != null && o.getName() != null
                && (o.getName().equalsIgnoreCase("Door") || o.getName().equalsIgnoreCase("Gate")
                || o.getName().equalsIgnoreCase("Ladder") || o.getName().equalsIgnoreCase("Trapdoor"))
                && (o.hasAction("Open") || o.hasAction("Climb-down") || o.hasAction("Climb-up")));
        if (barrier == null) {
            return false;
        }
        if (barrier.hasAction("Open")) {
            barrier.interact("Open");
            return true;
        }
        if (barrier.hasAction("Climb-down")) {
            barrier.interact("Climb-down");
            return true;
        }
        if (barrier.hasAction("Climb-up")) {
            barrier.interact("Climb-up");
            return true;
        }
        return false;
    }

    private boolean equipIfPresent(String itemName) {
        if (!Inventory.contains(itemName)) return false;
        return inventoryInteract(itemName, "Wield") || inventoryInteract(itemName, "Wear")
                || inventoryInteract(itemName, "Equip");
    }

    private boolean inventoryInteract(String itemName, String action) {
        try {
            var it = Inventory.getFirst(itemName);
            if (it == null) return false;
            InventoryActionHelper.interact(config, it, action);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String expectedInstructorFor(int progress, String locationPhase) {
        if ("guide".equals(locationPhase)) return "Gielinor Guide";
        if ("survival".equals(locationPhase)) return "Survival Expert";
        if ("chef".equals(locationPhase)) return "Master Chef";
        if ("quest".equals(locationPhase)) return "Quest Guide";
        if ("mining".equals(locationPhase)) return "Mining Instructor";
        if ("combat".equals(locationPhase)) return "Combat Instructor";
        if ("finance".equals(locationPhase)) return "Financial Advisor";
        if ("prayer".equals(locationPhase)) return "Brother Brace";
        if ("magic".equals(locationPhase)) return "Magic Instructor";
        if (progress < 10) return "Gielinor Guide";
        if (progress < 20) return "Survival Expert";
        if (progress < 30) return "Master Chef";
        if (progress < 40) return "Quest Guide";
        if (progress < 50) return "Mining Instructor";
        if (progress < 60) return "Combat Instructor";
        if (progress < 70) return "Financial Advisor";
        if (progress < 80) return "Brother Brace";
        return "Magic Instructor";
    }

    private WorldPoint expectedAnchorFor(int progress, String locationPhase) {
        if ("guide".equals(locationPhase)) return GUIDE_ANCHOR;
        if ("survival".equals(locationPhase)) return SURVIVAL_ANCHOR;
        if ("chef".equals(locationPhase)) return CHEF_ANCHOR;
        if ("quest".equals(locationPhase)) return QUEST_ANCHOR;
        if ("mining".equals(locationPhase)) return MINING_ANCHOR;
        if ("combat".equals(locationPhase)) return COMBAT_ANCHOR;
        if ("finance".equals(locationPhase)) return FINANCE_ANCHOR;
        if ("prayer".equals(locationPhase)) return PRAYER_ANCHOR;
        if ("magic".equals(locationPhase)) return MAGIC_ANCHOR;
        if (progress < 10) return GUIDE_ANCHOR;
        if (progress < 20) return SURVIVAL_ANCHOR;
        if (progress < 30) return CHEF_ANCHOR;
        if (progress < 40) return QUEST_ANCHOR;
        if (progress < 50) return MINING_ANCHOR;
        if (progress < 60) return COMBAT_ANCHOR;
        if (progress < 70) return FINANCE_ANCHOR;
        if (progress < 80) return PRAYER_ANCHOR;
        return MAGIC_ANCHOR;
    }

    private String resolveLocationPhase(WorldPoint p) {
        if (p == null) return "unknown";
        int x = p.getX();
        int y = p.getY();
        if (y >= 9490 && y <= 9545 && x >= 3062 && x <= 3092) return "mining";
        if (y >= 9490 && y <= 9545 && x >= 3093 && x <= 3135) return "combat";
        if (x <= 3079 && y <= 3100) return "chef";
        if (x <= 3095 && y >= 3113) return "quest";
        if (x >= 3115 && y >= 3115) return "finance";
        if (x >= 3115 && y >= 3098 && y <= 3114) return "prayer";
        if (x >= 3133 && y <= 3100) return "magic";
        if (x >= 3099 && y <= 3103) return "survival";
        // Startkamer Gielinor Guide: sommige tegels hebben y=3103 i.p.v. ≥3104 (was "unknown" → geen logische stap).
        if (x >= 3090 && y >= 3103 && y <= 3115) return "guide";
        return "unknown";
    }

    private boolean isWidgetTextVisible(String needle) {
        return findWidgetContaining(needle) != null;
    }

    private boolean clickWidgetContaining(String needle) {
        IWidget w = findWidgetContaining(needle);
        if (w == null) return false;
        try {
            w.interact(0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Tutorial: wrench-tab → subtab Display → dropdown naar Resizable - Modern (zoals jouw screenshot-volgorde).
     */
    private boolean tryEnsureResizableClientLayoutEarlyTut() {
        if (tutResizableLayoutDone) {
            return false;
        }
        if (isDisplayNamePanelVisible() || isCharacterCreatorPanelVisible()) {
            return false;
        }
        if (isResizableLayoutAlreadyVisibleInSidePanel()) {
            tutResizableLayoutDone = true;
            tutDisplayDropdownMaybeOpen = false;
            paint.setCurrentStatus("Tut: layout = Resizable");
            return false;
        }

        if (!isGameSettingsSidePanelOpen()) {
            tryOpenGameOptionsPanel();
            paint.setCurrentStatus("Tut: opties-paneel openen");
            return true;
        }

        if (isControlsSettingsVisible() && !isDisplaySettingsContextVisible()) {
            if (clickDisplayTabInSidePanel()) {
                paint.setCurrentStatus("Tut: Display-tab");
                return true;
            }
        }

        if (isDisplaySettingsContextVisible() || isFixedLayoutDropdownVisible()) {
            if (tutDisplayDropdownMaybeOpen) {
                if (clickWidgetContaining("resizable - modern")
                        || clickLayoutDropdownSelectContaining("resizable - modern")) {
                    tutDisplayDropdownMaybeOpen = false;
                    paint.setCurrentStatus("Tut: Resizable - Modern");
                    return true;
                }
                if (clickWidgetContaining("resizable - classic")
                        || clickLayoutDropdownSelectContaining("resizable - classic")) {
                    tutDisplayDropdownMaybeOpen = false;
                    paint.setCurrentStatus("Tut: Resizable - Classic");
                    return true;
                }
                tutDisplayDropdownMaybeOpen = false;
            }
            if (clickWidgetContaining("fixed - classic") || clickWidgetContaining("fixed - modern")) {
                tutDisplayDropdownMaybeOpen = true;
                paint.setCurrentStatus("Tut: layout-menu openen");
                return true;
            }
        }

        return false;
    }

    private boolean isGameSettingsSidePanelOpen() {
        return isWidgetTextVisible("controls settings")
                || isWidgetTextVisible("display settings")
                || isWidgetTextVisible("audio settings");
    }

    private boolean isControlsSettingsVisible() {
        return isWidgetTextVisible("controls settings");
    }

    private boolean isDisplaySettingsContextVisible() {
        return isWidgetTextVisible("display settings");
    }

    private boolean isFixedLayoutDropdownVisible() {
        return isWidgetTextVisible("fixed - classic") || isWidgetTextVisible("fixed - modern");
    }

    private boolean isResizableLayoutAlreadyVisibleInSidePanel() {
        IWidget modern = findWidgetContaining("resizable - modern");
        if (modern != null && !modern.isHidden()) {
            return true;
        }
        IWidget classic = findWidgetContaining("resizable - classic");
        return classic != null && !classic.isHidden();
    }

    /** Display settings dropdown: rijen met action Select (dump 116,40 — "Resizable - Modern layout"). */
    private boolean clickLayoutDropdownSelectContaining(String needle) {
        if (needle == null || needle.isBlank()) {
            return false;
        }
        String n = needle.toLowerCase(Locale.ROOT);
        for (int g = 0; g <= 800; g++) {
            for (int c = 0; c < 70; c++) {
                IWidget root = Widgets.get(g, c);
                if (root == null || root.isHidden()) {
                    continue;
                }
                for (IWidget w : flattenWidgets(root)) {
                    if (w == null || w.isHidden()) {
                        continue;
                    }
                    String t = w.getText();
                    if (t == null || !t.toLowerCase(Locale.ROOT).contains(n)) {
                        continue;
                    }
                    try {
                        if (w.hasAction("Select")) {
                            w.interact("Select");
                            return true;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return false;
    }

    private void tryOpenGameOptionsPanel() {
        try {
            Tabs.open(Tab.OPTIONS);
        } catch (Throwable ignored) {
        }
    }

    /** Display-subtab in het zijpaneel (niet verwarren met account "Set display name"). */
    private boolean clickDisplayTabInSidePanel() {
        for (int g = 0; g <= 800; g++) {
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
                    if (t == null) {
                        continue;
                    }
                    String s = t.trim();
                    if (s.equalsIgnoreCase("display") && s.length() <= 12) {
                        try {
                            w.interact(0);
                            return true;
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
        return false;
    }

    private IWidget findWidgetContaining(String needle) {
        if (needle == null || needle.isBlank()) return null;
        String n = needle.toLowerCase(Locale.ROOT);
        for (int g = 0; g <= 800; g++) {
            for (int c = 0; c < 40; c++) {
                IWidget root = Widgets.get(g, c);
                if (root == null || root.isHidden()) continue;
                for (IWidget w : flattenWidgets(root)) {
                    if (w == null || w.isHidden()) continue;
                    String t = w.getText();
                    if (t != null && t.toLowerCase(Locale.ROOT).contains(n)) {
                        return w;
                    }
                }
            }
        }
        return null;
    }

    private List<IWidget> flattenWidgets(IWidget root) {
        java.util.ArrayList<IWidget> out = new java.util.ArrayList<>();
        Deque<IWidget> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            IWidget w = q.poll();
            if (w == null) continue;
            out.add(w);
            try {
                IWidget[] children = w.getChildren();
                if (children == null) continue;
                for (IWidget ch : children) {
                    if (ch != null) q.add(ch);
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private int humanPause(int minMs, int maxMs) {
        int lo = Math.min(minMs, maxMs);
        int hi = Math.max(minMs, maxMs);
        if (hi <= lo) {
            return lo;
        }
        return lo + random.nextInt(hi - lo + 1);
    }

    private void maybeBumpReadPauseForNewHint() {
        if (random.nextInt(100) < 72) {
            nextActionAtMs = Math.max(nextActionAtMs, System.currentTimeMillis() + humanPause(400, 2200));
        }
    }

    private String readTutorialHintRaw() {
        try {
            LinkedHashSet<String> parts = new LinkedHashSet<>();
            for (int c = 0; c < 25; c++) {
                IWidget root = Widgets.get(HINT_GROUP, c);
                if (root == null || root.isHidden()) {
                    continue;
                }
                for (IWidget w : flattenWidgets(root)) {
                    if (w == null || w.isHidden()) {
                        continue;
                    }
                    String t = w.getText();
                    if (t == null) {
                        continue;
                    }
                    String s = t.trim();
                    if (!s.isEmpty()) {
                        parts.add(s);
                    }
                }
            }
            if (parts.isEmpty()) {
                return "";
            }
            return String.join(" ", parts);
        } catch (Throwable ignored) {
        }
        return "";
    }

    private String readTutorialHintLower() {
        return readTutorialHintRaw().toLowerCase(Locale.ROOT);
    }

    private boolean isNearGuideStartingRoom(IPlayer lp) {
        if (lp == null || lp.getWorldLocation() == null) {
            return false;
        }
        return GUIDE_ANCHOR.distanceTo(lp.getWorldLocation()) <= 14;
    }

    /**
     * Eerste tut-stappen: hint-widget (hele iface 263) en/of lage varp + startkamer — vóór resizable/tab-trucs.
     */
    private boolean tryEarlyGielinorGuideInteraction(int progress, String locationPhase, IPlayer lp) {
        if (Dialog.isOpen()) {
            return false;
        }
        String h = readTutorialHintLower();
        boolean hintWantsGuide = !h.isBlank()
                && (h.contains("gielinor guide") || h.contains("runescape guide")
                || (h.contains("getting started") && h.contains("gielinor"))
                || (h.contains("getting started") && h.contains("click") && h.contains("guide")));
        if (hintWantsGuide && (h.contains("click") || h.contains("ready to get started"))) {
            if (talkToInstructor("Gielinor Guide")) {
                return true;
            }
        }
        if (progress <= 15 && ("guide".equals(locationPhase) || "unknown".equals(locationPhase))
                && isNearGuideStartingRoom(lp)) {
            return talkToInstructor("Gielinor Guide");
        }
        return false;
    }

    /**
     * Tut-dialoog: geen blind altijd optie 0 — o.a. Ironman "nee", mainland "ja", met willekeurige pauzes.
     */
    private Integer handleTutorialDialogs() {
        if (!Dialog.isOpen()) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now < nextDialogClickAtMs) {
            return humanPause(120, 350);
        }
        nextDialogClickAtMs = now + humanPause(380, 950);

        if (Dialog.isViewingOptions()) {
            if (tryChooseDialogOption(s -> s != null && s.toLowerCase(Locale.ROOT).contains("not planning"))) {
                return humanPause(550, 1300);
            }
            if (tryChooseDialogOption(s -> s != null && s.toLowerCase(Locale.ROOT).contains("no, i'm not"))) {
                return humanPause(550, 1300);
            }
            if (tryChooseDialogOption(s -> s != null && s.equalsIgnoreCase("Yes."))) {
                return humanPause(500, 1200);
            }
            if (tryChooseDialogOption(s -> s != null && s.toLowerCase(Locale.ROOT).contains("go to the mainland")
                    && s.toLowerCase(Locale.ROOT).contains("yes"))) {
                return humanPause(500, 1200);
            }
            Dialog.chooseOption(0);
            return humanPause(520, 1100);
        }
        if (Dialog.canContinue()) {
            Dialog.continueSpace();
            if (random.nextInt(100) < 18) {
                nextDialogClickAtMs = now + humanPause(900, 2400);
            }
            return humanPause(480, 1200);
        }
        return humanPause(280, 600);
    }

    private boolean tryChooseDialogOption(java.util.function.Predicate<String> pred) {
        try {
            if (Dialog.hasOption(pred)) {
                Dialog.chooseOption(pred);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean tryInterfacePatternActions() {
        if (tryDismissBlockingTutorialPopup()) {
            return true;
        }
        if (tryCloseQuestRewardOrOverlay()) {
            return true;
        }
        if (tryCloseFloatingPollPanel()) {
            return true;
        }
        if (tryClickAccountManagementSideTab()) {
            return true;
        }
        if (tryOpenTabFromTutorialHint()) {
            return true;
        }
        if (tryPollBoothContinue()) {
            return true;
        }
        return false;
    }

    /** Game message + "Click here to continue" (o.a. Giant rat / re-attack). */
    private boolean tryDismissBlockingTutorialPopup() {
        if (!isWidgetTextVisible("already under attack")) {
            return false;
        }
        return clickWidgetContaining("click here to continue");
    }

    /** Poll-tutorial: zwevend paneel sluiten zodat de wereld weer klikbaar is. */
    private boolean tryCloseFloatingPollPanel() {
        String h = readTutorialHintLower();
        if (!h.contains("poll") && !isWidgetTextVisible("poll booths are found")) {
            return false;
        }
        return clickWidgetWithAction("Close Floating panel");
    }

    /** Zijpaneel — niet via Tab-enum; dump: actions bevatten "Account Management". */
    private boolean tryClickAccountManagementSideTab() {
        String h = readTutorialHintLower();
        if (!h.contains("account management") || !h.contains("flashing")) {
            return false;
        }
        return clickWidgetWithAction("Account Management");
    }

    private boolean clickWidgetWithAction(String action) {
        if (action == null || action.isBlank()) {
            return false;
        }
        for (int g = 0; g <= 800; g++) {
            for (int c = 0; c < 40; c++) {
                IWidget root = Widgets.get(g, c);
                if (root == null || root.isHidden()) {
                    continue;
                }
                for (IWidget w : flattenWidgets(root)) {
                    if (w == null || w.isHidden()) {
                        continue;
                    }
                    try {
                        if (w.hasAction(action)) {
                            w.interact(action);
                            return true;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return false;
    }

    private boolean tryCloseQuestRewardOrOverlay() {
        if (!isWidgetTextVisible("learning the ropes") && !isWidgetTextVisible("congratulations!")) {
            return false;
        }
        if (clickWidgetContaining("Close")) {
            return true;
        }
        return clickWidgetContaining("click here to continue");
    }

    private boolean tryPollBoothContinue() {
        if (!isWidgetTextVisible("poll booths are found")
                && !isWidgetTextVisible("you've already done that")) {
            return false;
        }
        return clickWidgetContaining("click here to continue");
    }

    private boolean tryOpenTabFromTutorialHint() {
        String h = readTutorialHintLower();
        if (h.isBlank()) {
            return false;
        }
        try {
            if ((h.contains("open your inventory") || h.contains("open the inventory")
                    || (h.contains("inventory") && (h.contains("you'll need to open") || h.contains("need to open"))))
                    && random.nextInt(100) < 88) {
                InventoryTabHelper.openInventoryTab(config);
                return true;
            }
            // Geen losse "flashing yellow": eerste tut-stap zegt "flashing yellow arrow" bij Gielinor Guide —
            // dat mag niet de quest-tab openen (dan praat de bot nooit met de guide).
            if ((h.contains("quest list") || h.contains("quest panel") || h.contains("quest journal")
                    || (h.contains("flashing") && h.contains("yellow") && h.contains("quest")))
                    && random.nextInt(100) < 88) {
                Tabs.open(Tab.QUESTS);
                return true;
            }
            if ((h.contains("worn equipment") || h.contains("equipping items") || h.contains("equipment tab")
                    || h.contains("equipment stats") || h.contains("worn inventory")
                    || h.contains("view equipment stats"))
                    && random.nextInt(100) < 88) {
                Tabs.open(Tab.EQUIPMENT);
                return true;
            }
            if ((h.contains("combat interface") || h.contains("crossed swords") || h.contains("combat options")
                    || h.contains("attack a rat") || h.contains("attack style") || h.contains("enter the cage"))
                    && random.nextInt(100) < 88) {
                Tabs.open(Tab.COMBAT);
                return true;
            }
            if ((h.contains("prayer menu") || h.contains("prayer list") || h.contains("open the prayer"))
                    && random.nextInt(100) < 88) {
                Tabs.open(Tab.PRAYER);
                return true;
            }
            if ((h.contains("magic interface") || h.contains("spellbook") || h.contains("wind strike")
                    || h.contains("magic casting") || h.contains("final menu") || h.contains("cast your first spell")
                    || (h.contains("flashing icon") && h.contains("magic")))
                    && random.nextInt(100) < 88) {
                Tabs.open(Tab.MAGIC);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean trySmithBronzeDaggerFromInterface() {
        try {
            IWidget w = Widgets.get(SMITH_BRONZE_DAGGER_GROUP, SMITH_BRONZE_DAGGER_CHILD);
            if (w == null || w.isHidden()) {
                return false;
            }
            String name = w.getName();
            String hay = (name == null ? "" : name.toLowerCase(Locale.ROOT));
            if (!hay.contains("dagger")) {
                return false;
            }
            if (w.hasAction("Smith")) {
                w.interact("Smith");
                return true;
            }
            w.interact(0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean tryMineRockFromContext() {
        if (Inventory.isFull()) {
            return false;
        }
        String h = readTutorialHintLower();
        boolean wantTin = h.contains("tin rock") || h.contains("tin rocks") || h.contains("mine tin")
                || (h.contains("tin") && h.contains("mine"));
        boolean wantCu = h.contains("copper rock") || h.contains("copper rocks") || h.contains("mine copper")
                || (h.contains("copper") && h.contains("mine"));
        if (!wantTin && !wantCu) {
            return false;
        }
        if (wantTin && !Inventory.contains("Tin ore")) {
            var rock = TileObjects.getNearest(o -> o != null && o.getName() != null
                    && o.getName().toLowerCase(Locale.ROOT).contains("tin")
                    && (o.hasAction("Mine") || o.hasAction("Prospect")));
            if (rock != null && rock.hasAction("Mine")) {
                rock.interact("Mine");
                return true;
            }
        }
        if (wantCu && !Inventory.contains("Copper ore")) {
            var rock = TileObjects.getNearest(o -> o != null && o.getName() != null
                    && o.getName().toLowerCase(Locale.ROOT).contains("copper")
                    && (o.hasAction("Mine") || o.hasAction("Prospect")));
            if (rock != null && rock.hasAction("Mine")) {
                rock.interact("Mine");
                return true;
            }
        }
        return false;
    }

    private boolean tryCastHomeTeleport() {
        try {
            SpellBook.Standard home = SpellBook.Standard.HOME_TELEPORT;
            if (home.canCast()) {
                Magic.cast(home);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void trackStep(String stepKey) {
        long now = System.currentTimeMillis();
        if (!stepKey.equals(lastStepKey)) {
            lastStepKey = stepKey;
            lastStepSinceMs = now;
            return;
        }
        if (lastStepSinceMs <= 0L) {
            lastStepSinceMs = now;
            return;
        }
        long elapsed = now - lastStepSinceMs;
        if (elapsed >= STEP_STUCK_MS) {
            stepStuck = true;
            stuckReason = "Tut-step vastgelopen (" + stepKey + ", " + (elapsed / 1000) + "s)";
        }
    }

    private void resetStepTracker() {
        lastStepKey = "";
        lastStepSinceMs = 0L;
        nextActionAtMs = 0L;
        nextNameTypeAtMs = 0L;
        lastEnterNameFieldClickMs = 0L;
        pendingDisplayName = "";
        creatorRandomizedThisOpen = false;
        creatorRandomClicksLeft = 0;
        tutResizableLayoutDone = false;
        tutDisplayDropdownMaybeOpen = false;
        stepStuck = false;
        stuckReason = "";
        lastHintSnapshot = "";
        rejectedDisplayPoolNames.clear();
        lastDisplayNameLookupAttempt = "";
    }

    public boolean isStepStuck() {
        return stepStuck;
    }

    public String getStuckReason() {
        return stuckReason == null ? "" : stuckReason;
    }

    public boolean isTutorialDoneNow() {
        IPlayer lp = Players.getLocal();
        return lp != null && (!isOnTutorialIsland(lp) || isTutorialFinishedByVarp());
    }

    public static boolean isOnTutorialIsland(IPlayer lp) {
        if (lp == null || lp.getWorldLocation() == null) {
            return false;
        }
        WorldPoint p = lp.getWorldLocation();
        if (p.getPlane() != 0) {
            return false;
        }
        int x = p.getX();
        int y = p.getY();
        if (x >= 3050 && x <= 3135 && y >= 3045 && y <= 3135) {
            return true;
        }
        // Mining- / combat-grot (instance-coördinaten — anders dacht de bot dat tut "klaar" was)
        return x >= 3055 && x <= 3135 && y >= 9480 && y <= 9545;
    }

    private static boolean isTutorialFinishedByVarp() {
        return readTutorialProgressVarp() >= TUTORIAL_DONE_VALUE;
    }

    private static int readTutorialProgressVarp() {
        try {
            return Vars.getVarp(TUTORIAL_PROGRESS_VARP);
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
