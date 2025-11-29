package com.archemidia.service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TimeService {
    private double gameTime = 0.25; // Start at Morning

    // Calculation for 1 Game Day = 30 Real Minutes (1800 seconds)
    // Tick runs every 100ms (0.1s) -> 10 ticks per second
    // Total ticks per day = 1800 * 10 = 18000 ticks
    // Increment per tick = 1.0 / 18000 = 0.000055555...

    private final double timeSpeed = 0.00005555555555555555; // Using a high precision value

    @Scheduled(fixedRate = 100)
    public void tick() {
        gameTime += timeSpeed;
        if (gameTime >= 1.0) {
            gameTime = 0.0;
        }
    }
    public double getGameTime() { return gameTime; }
}