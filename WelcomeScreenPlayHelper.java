package com.combatbot;

import net.runelite.api.MenuAction;
import net.storm.api.domain.widgets.IWidget;
import net.storm.sdk.game.Client;
import net.storm.sdk.script.blocking_events.WelcomeScreenEvent;

/**
 * Klik op "CLICK HERE TO PLAY" op het OSRS-welkomstscherm (lobby na Jagex-login).
 * Zelfde interactie als {@link net.storm.sdk.script.blocking_events.WelcomeScreenEvent#loop()}.
 */
public final class WelcomeScreenPlayHelper {

    private WelcomeScreenPlayHelper() {
    }

    public static boolean tryClickPlay() {
        if (!WelcomeScreenEvent.isWelcomeScreenOpen()) {
            return false;
        }
        IWidget play = WelcomeScreenEvent.getPlayButton();
        if (play == null) {
            return false;
        }
        Client.interact(1, MenuAction.CC_OP.getId(), -1, play.getId());
        return true;
    }
}
