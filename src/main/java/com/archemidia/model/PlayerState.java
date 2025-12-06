package com.archemidia.model;

import java.util.HashMap;
import java.util.Map;

public class PlayerState {
    private String playerId;
    private double x;
    private double y;
    private long lastProcessedSeqId = 0;

    // --- PLAYER STATS ---
    private int hp;
    private int maxHp;

    // --- INTERACTION & COMBAT STATE ---
    private boolean isAttacking = false;
    private long lastInteractionTime = 0;
    private static final long INTERACTION_COOLDOWN = 500;

    // Juice & Feedback States
    private boolean isKnockedBack = false;
    private long knockbackEndTime = 0;
    private boolean isInvulnerable = false;
    private long invulnerabilityEndTime = 0;

    private int facingDirection = 0;

    private Map<String, Integer> inventory = new HashMap<>();

    // --- REQUIRED: No-Arg Constructor for JSON ---
    public PlayerState() {
        this.maxHp = 100;
        this.hp = this.maxHp;
    }

    public PlayerState(String playerId, double x, double y) {
        this.playerId = playerId;
        this.x = x;
        this.y = y;
        this.maxHp = 100;
        this.hp = this.maxHp;
    }

    // --- INVENTORY MANAGEMENT ---
    public Map<String, Integer> getInventory() { return inventory; }

    // [FIX] Required for JSON Deserialization to restore items
    public void setInventory(Map<String, Integer> inventory) {
        this.inventory = inventory;
    }

    public void addItem(String itemName, int amount) {
        int current = inventory.getOrDefault(itemName, 0);
        inventory.put(itemName, current + amount);
    }

    public void removeItem(String itemName, int amount) {
        if (inventory.containsKey(itemName)) {
            int current = inventory.get(itemName);
            int newVal = current - amount;
            if (newVal <= 0) {
                inventory.remove(itemName);
            } else {
                inventory.put(itemName, newVal);
            }
        }
    }

    public boolean hasItem(String itemName, int amount) {
        return inventory.getOrDefault(itemName, 0) >= amount;
    }

    // --- HP Logic ---
    public void damage(int amount) {
        if (isInvulnerable()) return;

        this.hp -= amount;
        if (this.hp < 0) this.hp = 0;

        triggerInvulnerability(1000);
    }

    public void heal(int amount) {
        this.hp += amount;
        if (this.hp > this.maxHp) this.hp = this.maxHp;
    }

    public int getHp() { return hp; }
    public int getMaxHp() { return maxHp; }

    // [FIX] Setters for JSON Deserialization
    public void setHp(int hp) { this.hp = hp; }
    public void setMaxHp(int maxHp) { this.maxHp = maxHp; }

    // --- Interaction Logic ---
    public boolean canInteract() {
        long currentTime = System.currentTimeMillis();
        if (isKnockedBack()) return false;

        if (currentTime - lastInteractionTime >= INTERACTION_COOLDOWN) {
            lastInteractionTime = currentTime;
            return true;
        }
        return false;
    }

    public void setAttacking(boolean attacking) {
        this.isAttacking = attacking;
    }

    public boolean isAttacking() {
        return isAttacking;
    }

    // --- Juice / Feedback Methods ---

    public void triggerKnockback(long durationMs) {
        this.isKnockedBack = true;
        this.knockbackEndTime = System.currentTimeMillis() + durationMs;
    }

    public boolean isKnockedBack() {
        if (isKnockedBack && System.currentTimeMillis() > knockbackEndTime) {
            isKnockedBack = false;
        }
        return isKnockedBack;
    }

    public void triggerInvulnerability(long durationMs) {
        this.isInvulnerable = true;
        this.invulnerabilityEndTime = System.currentTimeMillis() + durationMs;
    }

    public boolean isInvulnerable() {
        if (isInvulnerable && System.currentTimeMillis() > invulnerabilityEndTime) {
            isInvulnerable = false;
        }
        return isInvulnerable;
    }

    public void setFacingDirection(int dir) {
        this.facingDirection = dir;
    }

    public int getFacingDirection() {
        return facingDirection;
    }

    // Getters & Setters
    public String getPlayerId() { return playerId; }
    public void setPlayerId(String playerId) { this.playerId = playerId; }

    public double getX() { return x; }
    public double getY() { return y; }
    public long getLastProcessedSeqId() { return lastProcessedSeqId; }

    public void setX(double x) { this.x = x; }
    public void setY(double y) { this.y = y; }
    public void setLastProcessedSeqId(long seqId) { this.lastProcessedSeqId = seqId; }
}