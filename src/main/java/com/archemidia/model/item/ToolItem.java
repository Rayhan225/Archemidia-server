package com.archemidia.model.item;

public class ToolItem extends Item {
    private int durability;
    private int miningPower;

    public ToolItem(String id, String name, int miningPower) {
        super(id, name, ItemType.TOOL, 1); // Tools usually don't stack
        this.miningPower = miningPower;
        this.durability = 100; // Default durability
    }

    public int getMiningPower() { return miningPower; }

    public void reduceDurability(int amount) {
        this.durability -= amount;
    }
}