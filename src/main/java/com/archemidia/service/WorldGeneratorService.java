package com.archemidia.service;

import com.archemidia.util.PerlinNoise;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

@Service
public class WorldGeneratorService {

    private final GameService gameService;

    // MUST match GameService seed for consistent biome shapes if GameService uses noise
    private final PerlinNoise biomeNoise = new PerlinNoise(12345);

    // Finite Map Radius
    private final int MAP_RADIUS = 100;

    // Biome Thresholds (Linear with noise)
    private final int SNOW_LIMIT = -40;
    private final int SAND_LIMIT = 40;

    public WorldGeneratorService(GameService gameService) {
        this.gameService = gameService;
    }

    public List<List<Integer>> generateChunk(int startX, int startY, int width, int height) {
        List<List<Integer>> chunk = new ArrayList<>();

        for (int y = 0; y < height; y++) {
            List<Integer> row = new ArrayList<>();
            for (int x = 0; x < width; x++) {
                int globalX = startX + x;
                int globalY = startY + y;

                int tileId = 0;

                // --- Finite Map Logic (Round Boundary) ---
                if (globalX * globalX + globalY * globalY > MAP_RADIUS * MAP_RADIUS) {
                    tileId = -1; // Void
                } else {
                    // --- Reverted to Linear Thresholds with Noise ---
                    // This creates wavy horizontal lines instead of blobs
                    double boundaryNoise = biomeNoise.noise(globalX * 0.05, 0, 0) * 15.0;

                    if (globalY < SNOW_LIMIT + boundaryNoise) {
                        tileId = 2; // Snow
                    } else if (globalY > SAND_LIMIT + boundaryNoise) {
                        tileId = 1; // Sand
                    } else {
                        tileId = 0; // Grass/Jungle
                    }
                }
                row.add(tileId);
            }
            chunk.add(row);
        }
        return chunk;
    }
}