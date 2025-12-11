package com.archemidia.model.item;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

// This allows Jackson to save different subclasses correctly if needed later
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public abstract class Item {
    protected String id;
    protected String name;
    protected ItemType type;
    protected int maxStack;

    public Item(String id, String name, ItemType type, int maxStack) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.maxStack = maxStack;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public ItemType getType() { return type; }
    public int getMaxStack() { return maxStack; }

    // Abstract method: Every item MUST define what happens when used
    // We pass the "user" (PlayerState) so the item can affect them
    // Returns true if the item was successfully used
    // We will implement logic later
    // public abstract boolean use(PlayerState user);
}