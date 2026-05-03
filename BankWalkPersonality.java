package com.combatbot;

/**
 * Houdt het actieve {@link AccountBehaviorProfileStore.Profile} bij voor bank-loopdoelen.
 * Gezet vanuit {@link CombatBotPlugin} (zelfde moment als anti-ban profiel); handlers roepen alleen
 * {@link BankHelper} aan zonder extra parameters.
 */
public final class BankWalkPersonality {

    private static volatile AccountBehaviorProfileStore.Profile activeProfile;

    private BankWalkPersonality() {
    }

    public static void setActive(AccountBehaviorProfileStore.Profile profile) {
        activeProfile = profile;
    }

    public static AccountBehaviorProfileStore.Profile getActive() {
        return activeProfile;
    }
}
