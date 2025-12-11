package com.archemidia.service;

import com.archemidia.model.item.*;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;

@Service
public class ItemRegistry {

    private final Map<String, Item> items = new HashMap<>();

    public ItemRegistry() {
        registerDefaults();
    }

    private void registerDefaults() {
        // Materials
        register(new MaterialItem("Wood", "Wood", 99));
        register(new MaterialItem("Stone", "Stone", 99));
        register(new MaterialItem("Rope", "Rope", 99));

        // Tools
        register(new ToolItem("Pickaxe", "Iron Pickaxe", 2));

        // Placeables (Treated as Items for inventory purposes)
        register(new Item("Crafting Table", "Crafting Table", ItemType.PLACEABLE, 1) {});
        register(new Item("Bonfire", "Bonfire", ItemType.PLACEABLE, 10) {});
        register(new Item("Fence", "Fence", ItemType.PLACEABLE, 100) {});
    }

    public void register(Item item) {
        items.put(item.getId(), item);
    }

    public Item getItem(String id) {
        return items.get(id);
    }
}