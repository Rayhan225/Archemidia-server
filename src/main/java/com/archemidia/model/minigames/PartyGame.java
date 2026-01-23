package com.archemidia.model.minigames;

public class PartyGame {
    public int score = 0;
    public int hypeLevel = 0; // 0 to 100
    public int multiplier = 1;
    public boolean gameOver = false;
    public String message = "Press 'A' to the beat!";

    // Simple spam protection
    private long lastHitTime = 0;

    public void start() {
        this.score = 0;
        this.hypeLevel = 0;
        this.multiplier = 1;
        this.gameOver = false;
        this.message = "Press 'A' to the beat!";
    }

    public void hit() {
        if (gameOver) return;

        // Basic spam check (limit to 10 hits per second max)
        long now = System.currentTimeMillis();
        if (now - lastHitTime < 100) return;
        lastHitTime = now;

        // Increase Hype
        hypeLevel += 5;
        if (hypeLevel > 100) hypeLevel = 100;

        // Score logic
        score += (10 * multiplier);

        // Escalation
        if (score > 500) multiplier = 2;
        if (score > 1500) multiplier = 4;
        if (score > 5000) multiplier = 8;

        // Dynamic messaging
        if (hypeLevel < 30) message = "Warming up...";
        else if (hypeLevel < 70) message = "It's getting loud!";
        else message = "MAXIMUM PARTY!";
    }

    public void miss() {
        // Penalty for missing a beat
        hypeLevel -= 10;
        if (hypeLevel < 0) hypeLevel = 0;
        message = "You lost the rhythm!";
    }
}