package com.archemidia.model.item;

public class ItemStack {
    private Item item;
    private int count;

    public ItemStack(Item item, int count) {
        this.item = item;
        this.count = count;
    }

    public Item getItem() { return item; }
    public int getCount() { return count; }

    public void add(int amount) {
        this.count += amount;
    }

    public void remove(int amount) {
        this.count -= amount;
    }
}