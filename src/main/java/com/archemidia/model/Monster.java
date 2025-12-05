package com.archemidia.model;

import java.util.Random;

public class Monster extends WorldObject {
    public String targetPlayerId = null;

    // Cooldowns
    public long lastAttackTime = 0;
    public long lastDashTime = 0;
    public long lastJumpTime = 0;
    public long lastEvadeTime = 0;

    // --- COMBAT STATS ---
    public double speed = 1.8;
    public double dx = 0;
    public double dy = 0;

    // Flag: Neutrality
    public boolean isAggravated = false;

    // AI State
    // Added EVADE and JUMP states
    public enum State { IDLE, WANDER, CHASE, ATTACK, RETREAT, HURT, DASH, EVADE, JUMP }
    public State state = State.IDLE;
    public long stateTimer = 0;

    // Personality
    public enum Personality { NORMAL, AGGRESSIVE, SHY, LAZY }
    public Personality personality = Personality.NORMAL;

    public boolean isRetreating = false;

    // --- REQUIRED FOR JSON DESERIALIZATION ---
    public Monster() {
        super();
    }

    public Monster(String type, int x, int y) {
        super(type, x, y);
        this.maxHp = 10;
        this.hp = this.maxHp;

        this.personality = Personality.values()[new Random().nextInt(Personality.values().length)];

        if (this.personality == Personality.AGGRESSIVE) {
            this.speed = 2.4; // Even Faster
            this.isAggravated = true;
        } else if (this.personality == Personality.LAZY) {
            this.speed = 1.2;
        }
    }
}