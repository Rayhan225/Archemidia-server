package com.archemidia.model;

public class WorldObject {
    public String id;
    public String type;
    public int hp;
    public int maxHp;
    public int x;
    public int y;

    // --- REQUIRED FOR JSON DESERIALIZATION ---
    public WorldObject() {}

    public WorldObject(String type, int x, int y) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.id = type.toLowerCase() + "_" + x + "_" + y;

        if (type.equals("Tree")) {
            this.maxHp = 5;
        } else {
            this.maxHp = 3;
        }
        this.hp = this.maxHp;
    }
}