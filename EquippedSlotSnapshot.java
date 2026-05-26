package com.combatbot;

/**
 * Eén equipment-slot in {@link AccountStateJsonStore#knownEquippedSlotsJson}.
 */
public final class EquippedSlotSnapshot {
    public String slot;
    public String name;
    public int id;
    public int qty;

    public EquippedSlotSnapshot() {
    }

    public EquippedSlotSnapshot(String slot, String name, int id, int qty) {
        this.slot = slot;
        this.name = name;
        this.id = id;
        this.qty = qty;
    }
}
