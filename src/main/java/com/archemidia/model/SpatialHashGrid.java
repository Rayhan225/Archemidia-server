package com.archemidia.util;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Spatial Hash Grid for optimizing range queries (finding things nearby).
 * It divides the world into large cells (buckets).
 */
public class SpatialHashGrid<T> {
    private final int cellSize;
    // Map<"Cell_X_Y", Set<ItemId>>
    private final Map<String, Set<String>> grid = new ConcurrentHashMap<>();

    public SpatialHashGrid(int cellSize) {
        this.cellSize = cellSize;
    }

    // Helper: Get Cell Key from World Coordinates
    private String getKey(double x, double y) {
        int cellX = (int) (x / cellSize);
        int cellY = (int) (y / cellSize);
        return cellX + "_" + cellY;
    }

    public void insert(String id, double x, double y) {
        String key = getKey(x, y);
        grid.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(id);
    }

    public void remove(String id, double x, double y) {
        String key = getKey(x, y);
        Set<String> cell = grid.get(key);
        if (cell != null) {
            cell.remove(id);
            if (cell.isEmpty()) {
                grid.remove(key);
            }
        }
    }

    public void update(String id, double oldX, double oldY, double newX, double newY) {
        String oldKey = getKey(oldX, oldY);
        String newKey = getKey(newX, newY);

        if (!oldKey.equals(newKey)) {
            remove(id, oldX, oldY);
            insert(id, newX, newY);
        }
    }

    /**
     * Returns a Set of IDs located in the same cell as (x,y) and its neighbors.
     * This drastically reduces the search space for collisions/AI.
     */
    public Set<String> getNearby(double x, double y) {
        Set<String> nearby = new HashSet<>();
        int cellX = (int) (x / cellSize);
        int cellY = (int) (y / cellSize);

        // Check 3x3 surrounding cells
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                String key = (cellX + i) + "_" + (cellY + j);
                Set<String> bucket = grid.get(key);
                if (bucket != null) {
                    nearby.addAll(bucket);
                }
            }
        }
        return nearby;
    }

    public void clear() {
        grid.clear();
    }
}