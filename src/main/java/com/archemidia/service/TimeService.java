package com.archemidia.service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TimeService {
    private double gameTime = 0.25;

    private final double timeSpeed = 0.00005555555555555555;

    @Scheduled(fixedRate = 100)
    public void tick() {
        gameTime += timeSpeed;
        if (gameTime >= 1.0) {
            gameTime = 0.0;
        }
    }
    public double getGameTime() { return gameTime; }
}