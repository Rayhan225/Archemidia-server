package com.archemidia.model.item;

public class MaterialItem extends Item {
    public MaterialItem(String id, String name, int maxStack) {
        super(id, name, ItemType.MATERIAL, maxStack);
    }
}