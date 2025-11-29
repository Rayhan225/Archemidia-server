package com.archemidia.util;

import com.archemidia.model.WorldObject;
import java.util.Map;

public class RaycastUtil {

    /**
     * Checks if there is a clear Line of Sight (LOS) between (x1, y1) and (x2, y2).
     * Returns true if clear, false if blocked by an object.
     */
    public static boolean hasLineOfSight(double x1, double y1, double x2, double y2, Map<String, WorldObject> activeObjects) {
        double distance = Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));

        // 1. Normalized Direction Vector
        double dx = (x2 - x1) / distance;
        double dy = (y2 - y1) / distance;

        // 2. Step size (Check every 32 pixels - half a tile)
        // Smaller steps = more accurate but slower. 32 is a good balance.
        int steps = (int) (distance / 32.0);

        double currentX = x1;
        double currentY = y1;

        for (int i = 0; i < steps; i++) {
            currentX += dx * 32.0;
            currentY += dy * 32.0;

            if (isTileBlocked(currentX, currentY, activeObjects)) {
                return false; // Vision Blocked
            }
        }

        return true; // No obstacles found
    }

    private static boolean isTileBlocked(double x, double y, Map<String, WorldObject> activeObjects) {
        int tx = (int) (x / 64.0);
        int ty = (int) (y / 64.0);

        // Check if an object exists at this tile
        // Note: This assumes ALL objects block sight.
        // If you have 'Grass' objects in activeObjects, you might need to check obj.type != "Grass"
        return activeObjects.containsKey(tx + "_" + ty);
    }
}