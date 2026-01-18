package com.archemidia.util;

import com.archemidia.model.WorldObject;
import java.util.Map;

public class RaycastUtil {

    public static boolean hasLineOfSight(double x1, double y1, double x2, double y2, Map<String, WorldObject> activeObjects) {
        double distance = Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));

        double dx = (x2 - x1) / distance;
        double dy = (y2 - y1) / distance;

        int steps = (int) (distance / 32.0);

        double currentX = x1;
        double currentY = y1;

        for (int i = 0; i < steps; i++) {
            currentX += dx * 32.0;
            currentY += dy * 32.0;

            if (isTileBlocked(currentX, currentY, activeObjects)) {
                return false;
            }
        }

        return true;
    }

    private static boolean isTileBlocked(double x, double y, Map<String, WorldObject> activeObjects) {
        int tx = (int) (x / 64.0);
        int ty = (int) (y / 64.0);

        return activeObjects.containsKey(tx + "_" + ty);
    }
}