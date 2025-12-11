package com.archemidia.model;

import com.archemidia.model.item.Item;
import com.archemidia.model.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    // [CHANGED] Map -> List<ItemStack>
    private List<ItemStack> inventory = new ArrayList<>();

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

    // --- NEW INVENTORY MANAGEMENT ---

    // For Internal Logic
    public List<ItemStack> getInventory() { return inventory; }
    public void setInventory(List<ItemStack> inventory) { this.inventory = inventory; }

    // For Client Compatibility (converts back to simple Map)
    public Map<String, Integer> getInventoryAsMap() {
        return inventory.stream()
                .collect(Collectors.toMap(
                        stack -> stack.getItem().getId(),
                        ItemStack::getCount
                ));
    }

    public void addItem(Item item, int amount) {
        // 1. Try to stack with existing
        for (ItemStack stack : inventory) {
            if (stack.getItem().getId().equals(item.getId())) {
                // Future: Check stack.getItem().getMaxStack()
                stack.add(amount);
                return;
            }
        }
        // 2. Else add new slot
        inventory.add(new ItemStack(item, amount));
    }

    public void removeItem(String itemId, int amount) {
        ItemStack toRemove = null;
        for (ItemStack stack : inventory) {
            if (stack.getItem().getId().equals(itemId)) {
                stack.remove(amount);
                if (stack.getCount() <= 0) {
                    toRemove = stack;
                }
                break;
            }
        }
        if (toRemove != null) {
            inventory.remove(toRemove);
        }
    }

    public boolean hasItem(String itemId, int amount) {
        for (ItemStack stack : inventory) {
            if (stack.getItem().getId().equals(itemId)) {
                return stack.getCount() >= amount;
            }
        }
        return false;
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

    public void setAttacking(boolean attacking) { this.isAttacking = attacking; }
    public boolean isAttacking() { return isAttacking; }

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

    public void setFacingDirection(int dir) { this.facingDirection = dir; }
    public int getFacingDirection() { return facingDirection; }

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