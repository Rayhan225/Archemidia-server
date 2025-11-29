package com.archemidia.service;

import com.archemidia.model.Monster;
import com.archemidia.model.PlayerState;
import com.archemidia.model.WorldObject;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class GameService {

    private final Map<String, PlayerState> playerStates = new ConcurrentHashMap<>();

    // Stores World Objects (Trees, Rocks) - Key: "x_y"
    private final Map<String, WorldObject> activeObjects = new ConcurrentHashMap<>();

    private final Map<String, Monster> activeMonsters = new ConcurrentHashMap<>();
    private final List<String> destroyedObjectIds = new ArrayList<>();

    private final int TILE_SIZE = 64;
    private final int MAX_MONSTERS = 4;

    // AI Config
    private final double CHASE_START_DIST = 4 * 64.0;
    private final double CHASE_STOP_DIST = 6 * 64.0;
    private final double ATTACK_RANGE = 64.0;
    private final long ATTACK_COOLDOWN = 1500;

    // Finite Map Config
    public static final int MAP_RADIUS = 100;

    // Biome Thresholds
    private final int SNOW_LIMIT = -40;
    private final int SAND_LIMIT = 40;

    public GameService() {
        initializeFixedMap();
    }

    // --- Helper: Determine Biome at (x,y) ---
    // Returns 0=Grass, 1=Sand, 2=Snow
    // Note: Since we are removing procedural generation, this helper is mainly for initial placement logic.
    // The "saved" map is simply the state of activeObjects.
    // If you want the terrain to persist visually as well, the WorldGeneratorService needs to match this logic or read from a saved terrain map.
    // Assuming for now we want simple bands as per "current map design" mentioned.
    public int getTerrainAt(int x, int y) {
        // Circular Boundary Check
        if (x * x + y * y > MAP_RADIUS * MAP_RADIUS) return -1; // Void

        // Simple Linear Bands
        if (y < SNOW_LIMIT) return 2; // Snow
        if (y > SAND_LIMIT) return 1; // Sand
        return 0; // Grass
    }

    // --- Generate a Fixed, Persistent Map at Startup ---
    private void initializeFixedMap() {
        Random rand = new Random(12345); // Fixed seed for object placement

        // Iterate through the finite map area
        for (int x = -MAP_RADIUS; x <= MAP_RADIUS; x++) {
            for (int y = -MAP_RADIUS; y <= MAP_RADIUS; y++) {

                // Check if valid terrain
                int biome = getTerrainAt(x, y);
                if (biome == -1) continue; // Void

                // Safe Zone at Center
                if (x > -5 && x < 5 && y > -5 && y < 5) continue;

                // Randomly place objects based on Biome
                double roll = rand.nextDouble();

                if (biome == 2) { // SNOW
                    if (roll < 0.025) addObject("Snow Tree", x, y);
                    else if (roll < 0.035) addObject("Snow Rock", x, y);
                }
                else if (biome == 1) { // SAND
                    if (roll < 0.008) addObject("Palm Tree", x, y);
                    else if (roll < 0.018) addObject("Sand Rock", x, y);
                    else if (roll < 0.035) addObject("Cactus", x, y);
                }
                else { // GRASS
                    if (roll < 0.025) addObject("Tree", x, y);
                    else if (roll < 0.035) addObject("Stone", x, y);
                    else if (roll < 0.045) addObject("Trunk", x, y);
                }
            }
        }
        System.out.println("Fixed Map Initialized with " + activeObjects.size() + " objects.");
    }

    private void addObject(String type, int x, int y) {
        String key = x + "_" + y;
        activeObjects.put(key, new WorldObject(type, x, y));
    }

    @Scheduled(fixedRate = 50)
    public void gameLoop() {
        long now = System.currentTimeMillis();
        if (activeMonsters.size() < MAX_MONSTERS) {
            if (ThreadLocalRandom.current().nextDouble() < 0.05) {
                spawnRandomMonster();
            }
        }
        for (Monster monster : activeMonsters.values()) {
            if (monster.hp <= 0) continue;
            updateMonsterAI(monster, now);
        }
    }

    private void spawnRandomMonster() {
        int bound = MAP_RADIUS - 5;
        int rx = ThreadLocalRandom.current().nextInt(-bound, bound);
        int ry = ThreadLocalRandom.current().nextInt(-bound, bound);

        if (rx * rx + ry * ry > bound * bound) return;

        double worldX = rx * TILE_SIZE + (TILE_SIZE / 2.0);
        double worldY = ry * TILE_SIZE + (TILE_SIZE / 2.0);

        if (!isBlocked(worldX, worldY)) {
            spawnMonster("Slime", (int)worldX, (int)worldY);
        }
    }

    private void updateMonsterAI(Monster m, long now) {
        PlayerState target = null;
        if (m.targetPlayerId != null) {
            target = playerStates.get(m.targetPlayerId);
            if (target == null) {
                m.targetPlayerId = null;
                m.isRetreating = false;
            }
        }
        if (target == null) {
            for (PlayerState p : playerStates.values()) {
                double d = getDistance(m.x, m.y, p.getX(), p.getY());
                if (d < CHASE_START_DIST) {
                    target = p;
                    m.targetPlayerId = p.getPlayerId();
                    break;
                }
            }
        }
        if (target != null) {
            double dist = getDistance(m.x, m.y, target.getX(), target.getY());
            if (m.hp <= 7 && !m.isRetreating && m.hp > 0) {
                if (ThreadLocalRandom.current().nextDouble() < 0.02) {
                    m.isRetreating = true;
                    m.stateTimer = now + 3000;
                }
            }
            if (dist > CHASE_STOP_DIST) {
                m.targetPlayerId = null;
                m.isRetreating = false;
                m.state = Monster.State.IDLE;
                return;
            }
            if (m.isRetreating) {
                m.state = Monster.State.RETREAT;
                if (now > m.stateTimer) {
                    m.isRetreating = false;
                } else {
                    double angle = Math.atan2(m.y - target.getY(), m.x - target.getX());
                    moveMonsterSmart(m, angle);
                }
            }
            else if (dist <= ATTACK_RANGE) {
                if (now - m.lastAttackTime > ATTACK_COOLDOWN) {
                    m.state = Monster.State.ATTACK;
                    m.lastAttackTime = now;
                    target.damage(1);
                }
                m.dx = 0; m.dy = 0;
            }
            else {
                m.state = Monster.State.CHASE;
                double angleToPlayer = Math.atan2(target.getY() - m.y, target.getX() - m.x);
                moveMonsterSmart(m, angleToPlayer);
            }
        }
        else {
            handlePassiveBehavior(m, now);
        }
    }

    private void handlePassiveBehavior(Monster m, long now) {
        if (now > m.stateTimer) {
            if (m.state == Monster.State.WANDER) {
                m.state = Monster.State.IDLE;
                m.stateTimer = now + ThreadLocalRandom.current().nextLong(2000, 4000);
            } else {
                m.state = Monster.State.WANDER;
                m.stateTimer = now + ThreadLocalRandom.current().nextLong(1000, 3000);
                double wanderAngle = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
                m.dx = Math.cos(wanderAngle) * (m.speed * 0.5);
                m.dy = Math.sin(wanderAngle) * (m.speed * 0.5);
            }
        }
        if (m.state == Monster.State.WANDER) {
            double nextX = m.x + m.dx;
            double nextY = m.y + m.dy;
            if (!isBlocked(nextX, nextY)) {
                m.x = (int)nextX;
                m.y = (int)nextY;
            } else {
                m.state = Monster.State.IDLE;
                m.stateTimer = now + 1000;
            }
        }
    }

    private void moveMonsterSmart(Monster m, double angle) {
        double newX = m.x + Math.cos(angle) * m.speed;
        double newY = m.y + Math.sin(angle) * m.speed;
        if (!isBlocked(newX, newY)) {
            m.x = (int)newX;
            m.y = (int)newY;
        } else {
            for (double offset : new double[]{-0.6, 0.6}) {
                double tryAngle = angle + offset;
                double tryX = m.x + Math.cos(tryAngle) * m.speed;
                double tryY = m.y + Math.sin(tryAngle) * m.speed;
                if (!isBlocked(tryX, tryY)) {
                    m.x = (int)tryX;
                    m.y = (int)tryY;
                    break;
                }
            }
        }
    }

    public WorldObject processInteraction(String sessionId, int targetX, int targetY) {
        PlayerState player = playerStates.get(sessionId);
        if (player == null) return null;
        double playerX = player.getX();
        double playerY = player.getY();

        double hitCenterX = (targetX * TILE_SIZE) + (TILE_SIZE / 2.0);
        double hitCenterY = (targetY * TILE_SIZE) + (TILE_SIZE / 2.0);

        for (Monster m : activeMonsters.values()) {
            boolean collisionHit = getDistance(playerX, playerY, m.x, m.y) < 64;
            boolean directionalHit = getDistance(hitCenterX, hitCenterY, m.x, m.y) < 64;

            if (collisionHit || directionalHit) {
                m.hp--;
                m.targetPlayerId = sessionId;
                m.state = Monster.State.HURT;
                m.stateTimer = System.currentTimeMillis() + 400;
                double angle = Math.atan2(m.y - playerY, m.x - playerX);
                double knX = m.x + Math.cos(angle) * 15.0;
                double knY = m.y + Math.sin(angle) * 15.0;
                if(!isBlocked(knX, knY)) {
                    m.x = (int)knX;
                    m.y = (int)knY;
                }
                if (m.hp <= 0) {
                    activeMonsters.remove(m.id);
                    return m;
                }
                return m;
            }
        }

        String objKey = targetX + "_" + targetY;
        // Interaction is now purely based on activeObjects map
        WorldObject obj = activeObjects.get(objKey);

        if (obj == null) return null;
        if (obj.type.equals("Crafting Table")) return obj;

        obj.hp--;
        if (obj.hp <= 0) {
            destroyedObjectIds.add(objKey); // Keep track just in case, or just remove
            activeObjects.remove(objKey);
        }
        return obj;
    }

    private boolean isValidPosition(double x, double y) {
        int tx = (int)(x / TILE_SIZE);
        int ty = (int)(y / TILE_SIZE);
        // Map Bound Check
        return getTerrainAt(tx, ty) != -1;
    }

    private boolean isBlocked(double x, double y) {
        int tileX = (int)(x / 64.0);
        int tileY = (int)(y / 64.0);

        // 1. Terrain Check (Void)
        if (getTerrainAt(tileX, tileY) == -1) return true;

        // 2. Object Check (The ONLY source of truth now)
        String key = tileX + "_" + tileY;
        return activeObjects.containsKey(key);
    }

    private double getDistance(double x1, double y1, double x2, double y2) {
        return Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));
    }

    public void spawnMonster(String type, int x, int y) {
        if (activeMonsters.size() >= MAX_MONSTERS) return;
        String id = "monster_" + System.nanoTime();
        Monster m = new Monster(type, x, y);
        m.id = id;
        activeMonsters.put(id, m);
    }

    public Map<String, Monster> getActiveMonsters() { return activeMonsters; }
    public Map<String, WorldObject> getActiveObjects() { return activeObjects; }

    public PlayerState onPlayerConnect(String sessionId) {
        PlayerState newState = new PlayerState(sessionId, 0, 0);
        newState.addItem("Crafting Table", 1);
        playerStates.put(sessionId, newState);
        return newState;
    }

    public void onPlayerDisconnect(String sessionId) { playerStates.remove(sessionId); }
    public PlayerState getPlayer(String sessionId) { return playerStates.get(sessionId); }

    public PlayerState processMove(String sessionId, double requestedX, double requestedY, long seqId) {
        PlayerState player = playerStates.get(sessionId);
        if (player != null && isValidPosition(requestedX, requestedY)) {
            if (!isBlocked(requestedX, requestedY)) {
                player.setX(requestedX);
                player.setY(requestedY);
                player.setLastProcessedSeqId(seqId);
            }
        }
        return player;
    }

    public PlayerState processPickup(String sessionId, String itemType) {
        PlayerState player = playerStates.get(sessionId);
        if (player != null) player.addItem(itemType, 1);
        return player;
    }
    public PlayerState processRemoveItem(String sessionId, String itemType, int amount) {
        PlayerState player = playerStates.get(sessionId);
        if (player != null) player.removeItem(itemType, amount);
        return player;
    }
    public boolean processCrafting(String sessionId, String recipe) {
        PlayerState player = playerStates.get(sessionId);
        if (player == null) return false;

        if (recipe.equals("Pickaxe")) {
            if (player.hasItem("Wood", 3) && player.hasItem("Stone", 2) && player.hasItem("Rope", 1)) {
                player.removeItem("Wood", 3);
                player.removeItem("Stone", 2);
                player.removeItem("Rope", 1);
                player.addItem("Pickaxe", 1);
                return true;
            }
        }
        else if (recipe.equals("Bonfire")) {
            if (player.hasItem("Wood", 10) && player.hasItem("Stone", 5)) {
                player.removeItem("Wood", 10);
                player.removeItem("Stone", 5);
                player.addItem("Bonfire", 1);
                return true;
            }
        }

        return false;
    }
    public boolean processPlaceObject(String sessionId, String type, int x, int y) {
        PlayerState player = playerStates.get(sessionId);
        if (player == null || !player.hasItem(type, 1)) return false;

        // Terrain Bound Check
        if (getTerrainAt(x, y) == -1) return false;

        String objKey = x + "_" + y;
        // Check if there is ALREADY an object there
        if (activeObjects.containsKey(objKey)) return false;

        WorldObject obj = new WorldObject(type, x, y);
        activeObjects.put(objKey, obj);
        player.removeItem(type, 1);
        return true;
    }
    public boolean processPickupObject(String sessionId, int x, int y) {
        String objKey = x + "_" + y;
        WorldObject obj = activeObjects.get(objKey);
        if (obj != null) {
            if (obj.type.equals("Crafting Table") || obj.type.equals("Bonfire")) {
                activeObjects.remove(objKey);
                destroyedObjectIds.add(objKey);
                return true;
            }
        }
        return false;
    }

    public List<DropResult> calculateDrops(String type, boolean destroyed) {
        List<DropResult> drops = new ArrayList<>();
        if (type.equals("Slime")) {
            drops.add(new DropResult("Rope", 1));
            return drops;
        }
        if (type.equals("Crafting Table")) return drops;

        if (type.equals("Bonfire")) {
            drops.add(new DropResult("Bonfire", 1));
            return drops;
        }

        int amount = destroyed ? ThreadLocalRandom.current().nextInt(3, 6) : 1;
        if (type.equals("Tree") || type.equals("Snow Tree") || type.equals("Palm Tree")) drops.add(new DropResult("Wood", amount));
        else if (type.equals("Stone") || type.equals("Snow Rock") || type.equals("Sand Rock")) drops.add(new DropResult("Stone", amount));
        else if (type.equals("Cactus")) drops.add(new DropResult("Wood", 1));
        else if (type.equals("Trunk")) {
            drops.add(new DropResult("Wood", amount));
            drops.add(new DropResult("Rope", amount));
        }
        return drops;
    }

    public static class DropResult {
        public String type;
        public int amount;
        public DropResult(String t, int a) { type = t; amount = a; }
    }
}