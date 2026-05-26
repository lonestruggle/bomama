package com.combatbot;

import net.storm.api.domain.items.IInventoryItem;
import net.storm.sdk.items.Inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Menselijk erts-droppen: willekeurig patroon (alles, helft, volgorde, links/rechts, shuffle).
 */
public final class MiningDropHelper {

    /** Genie random-event lamp (item id 2528; zelfde als fishing/imps). */
    public static final int GENIE_LAMP_ITEM_ID = 2528;

    public enum DropStyle {
        ALL_TOP_TO_BOTTOM,
        ALL_BOTTOM_TO_TOP,
        HALF_RANDOM,
        LEFT_THEN_RIGHT,
        RIGHT_THEN_LEFT,
        SHUFFLE_ALL
    }

    private MiningDropHelper() {
    }

    /** Normale mining-loot (erts, ongeslepen gems, …) — geen reden om meteen te banken. */
    public static boolean isMiningLootItem(IInventoryItem item) {
        if (item == null || item.getName() == null) {
            return false;
        }
        String n = item.getName().toLowerCase(Locale.ROOT);
        return n.contains("ore") || n.equals("coal") || n.equals("clay") || n.startsWith("uncut");
    }

    public static boolean isAllowedWhileMining(IInventoryItem item) {
        if (item == null || item.getName() == null) {
            return true;
        }
        if (item.getId() == GENIE_LAMP_ITEM_ID) {
            return true;
        }
        String n = item.getName().toLowerCase(Locale.ROOT);
        if (n.contains("lamp")) {
            return true;
        }
        return n.contains("pickaxe")
                || isMiningLootItem(item)
                || n.equals("hammer")
                || n.equals("chisel")
                || item.hasAction("Eat")
                || item.hasAction("Drink")
                || n.contains("coins");
    }

    public static boolean isDroppableMiningLoot(IInventoryItem item) {
        if (item == null || item.getName() == null) {
            return false;
        }
        if (item.getName().toLowerCase(Locale.ROOT).contains("pickaxe")) {
            return false;
        }
        return isMiningLootItem(item);
    }

    public static List<IInventoryItem> collectDroppableOres() {
        List<IInventoryItem> out = new ArrayList<>();
        try {
            List<IInventoryItem> all = Inventory.getAll();
            if (all == null) {
                return out;
            }
            for (IInventoryItem item : all) {
                if (isDroppableMiningLoot(item)) {
                    out.add(item);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static DropStyle pickStyle(Random rng) {
        DropStyle[] styles = DropStyle.values();
        return styles[rng.nextInt(styles.length)];
    }

    /**
     * Hoeveel erts deze tick droppen (menselijk: soms 1, soms halve inv, soms alles).
     */
    public static int pickDropBatchSize(int remaining, int totalOreStacks, Random rng) {
        if (remaining <= 0) {
            return 0;
        }
        if (remaining == 1) {
            return 1;
        }
        int roll = rng.nextInt(100);
        if (totalOreStacks >= 18 || roll < 30) {
            return remaining;
        }
        if (roll < 48) {
            return 1;
        }
        if (roll < 62) {
            return Math.min(remaining, 2 + rng.nextInt(3));
        }
        if (roll < 78) {
            return Math.min(remaining, 4 + rng.nextInt(5));
        }
        int half = Math.max(3, (remaining + 1) / 2);
        return Math.min(remaining, half + rng.nextInt(Math.max(1, remaining - half + 1)));
    }

    /** Bouwt drop-volgorde voor één inventaris-sessie. */
    public static List<IInventoryItem> orderForStyle(List<IInventoryItem> items, DropStyle style, Random rng) {
        if (items == null || items.isEmpty()) {
            return new ArrayList<>();
        }
        List<IInventoryItem> list = new ArrayList<>(items);
        switch (style) {
            case ALL_BOTTOM_TO_TOP:
                Collections.reverse(list);
                break;
            case HALF_RANDOM: {
                int keep = Math.max(1, list.size() / 2);
                Collections.shuffle(list, rng);
                if (list.size() > keep) {
                    list = new ArrayList<>(list.subList(0, keep));
                }
                break;
            }
            case LEFT_THEN_RIGHT:
            case RIGHT_THEN_LEFT: {
                List<IInventoryItem> even = new ArrayList<>();
                List<IInventoryItem> odd = new ArrayList<>();
                for (int i = 0; i < list.size(); i++) {
                    if (i % 2 == 0) {
                        even.add(list.get(i));
                    } else {
                        odd.add(list.get(i));
                    }
                }
                list = new ArrayList<>();
                if (style == DropStyle.LEFT_THEN_RIGHT) {
                    list.addAll(even);
                    list.addAll(odd);
                } else {
                    list.addAll(odd);
                    list.addAll(even);
                }
                break;
            }
            case SHUFFLE_ALL:
                Collections.shuffle(list, rng);
                break;
            case ALL_TOP_TO_BOTTOM:
            default:
                break;
        }
        return list;
    }
}
