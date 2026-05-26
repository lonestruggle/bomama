package com.combatbot;

import net.runelite.api.Quest;
import net.storm.api.Static;
import net.storm.api.quests.IQuests;
import net.storm.sdk.quests.Quests;

/**
 * Quest-voltooiing via Storm {@link IQuests} ({@link Static#getQuests()}), met fallback naar {@link Quests}.
 * Zie <a href="https://stormjavadocs.z6.web.core.windows.net/net/storm/api/quests/IQuests.html">IQuests</a>.
 */
public final class StormQuestHelper {

    private StormQuestHelper() {
    }

    /**
     * F2P-quests die de bot kan overslaan (quest-modus / start skill) als {@link #isQuestFinished(Quest)} true is.
     * Alleen {@link Quest#VAMPYRE_SLAYER} wordt nu ook in {@link AccountQuestProgressStore} weggeschreven;
     * andere entries zijn vooruitblik / logging.
     */
    public static final Quest[] F2P_QUESTS_CHECK_WHEN_IN_ROTATION = {
            Quest.VAMPYRE_SLAYER,
            Quest.COOKS_ASSISTANT,
            Quest.DEMON_SLAYER,
            Quest.DORICS_QUEST,
            Quest.DRAGON_SLAYER_I,
            Quest.ERNEST_THE_CHICKEN,
            Quest.GOBLIN_DIPLOMACY,
            Quest.IMP_CATCHER,
            Quest.THE_KNIGHTS_SWORD,
            Quest.PIRATES_TREASURE,
            Quest.PRINCE_ALI_RESCUE,
            Quest.THE_RESTLESS_GHOST,
            Quest.RUNE_MYSTERIES,
            Quest.SHEEP_SHEARER,
            Quest.SHIELD_OF_ARRAV,
            Quest.WITCHS_POTION,
            Quest.X_MARKS_THE_SPOT,
            Quest.BELOW_ICE_MOUNTAIN,
            Quest.MISTHALIN_MYSTERY,
            Quest.THE_CORSAIR_CURSE,
    };

    public static boolean isQuestFinished(Quest quest) {
        if (quest == null) {
            return false;
        }
        try {
            IQuests iq = Static.getQuests();
            if (iq != null) {
                return iq.isFinished(quest);
            }
        } catch (Throwable ignored) {
        }
        try {
            return Quests.isFinished(quest);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
