package com.archemidia.service;

import com.archemidia.model.Monster;
import com.archemidia.model.PlayerState;
import com.archemidia.model.WorldObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class GameService {

    private final Map<String, PlayerState> playerStates = new ConcurrentHashMap<>();
    private final Map<String, WorldObject> activeObjects = new ConcurrentHashMap<>();
    private final Map<String, Monster> activeMonsters = new ConcurrentHashMap<>();
    private final List<String> destroyedObjectIds = new ArrayList<>();

    private final PersistenceService persistenceService;

    private final int TILE_SIZE = 64;

    // [FIX 1] Reduced Collision Radius to be more forgiving than Client
    // This prevents "grazing" collisions from causing rubber-banding
    private final double PLAYER_RADIUS = 10.0;

    private final int MAX_MONSTERS = 5;

    // AI Config
    // [FIX 2] Logic updated: Start Dist is irrelevant for passive mobs,
    // but Stop Dist is used to give up chase.
    private final double CHASE_STOP_DIST = 10 * 64.0; // Give up if player runs far
    private final double ATTACK_RANGE = 64.0;
    private final long ATTACK_COOLDOWN = 1500;

    public static final int MAP_RADIUS = 100;

    private final int SNOW_LIMIT = -30;
    private final int SAND_LIMIT = 30;

    public GameService(PersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    // --- LIFECYCLE ---
    @PostConstruct
    public void init() {
        PersistenceService.SaveData data = persistenceService.loadData();
        if (data != null && data.objects != null && !data.objects.isEmpty()) {
            this.activeObjects.putAll(data.objects);
            System.out.println(" [GameService] Loaded " + activeObjects.size() + " objects.");
        } else {
            initializeFixedMap();
        }
    }

    @PreDestroy
    public void cleanup() {
        persistenceService.saveData(activeObjects, playerStates);
    }

    @Scheduled(fixedRate = 30000)
    public void autoSave() {
        persistenceService.saveData(activeObjects, playerStates);
    }

    // --- DETERMINISTIC GENERATION (Matches Godot 64-bit Hash) ---
    private double getHashNoise(int x, int y) {
        long seed = 12345;
        long n = (long)x * 331 + (long)y * 433 + seed;
        n = (n << 13) ^ n;
        n = (n * (n * n * 15731 + 789221) + 1376312589) & 0x7fffffff;
        return (double) n / 2147483647.0;
    }

    public int getTerrainAt(int x, int y) {
        if (x * x + y * y > MAP_RADIUS * MAP_RADIUS) return -1;
        double boundaryNoise = (getHashNoise(x, 0) - 0.5) * 10.0;
        if (y < SNOW_LIMIT + boundaryNoise) return 2;
        if (y > SAND_LIMIT + boundaryNoise) return 1;
        return 0;
    }

    private void initializeFixedMap() {
        activeObjects.clear();
        for (int x = -MAP_RADIUS; x <= MAP_RADIUS; x++) {
            for (int y = -MAP_RADIUS; y <= MAP_RADIUS; y++) {
                if (x * x + y * y > MAP_RADIUS * MAP_RADIUS) continue;
                int biome = getTerrainAt(x, y);
                if (biome == -1) continue;
                if (x > -5 && x < 5 && y > -5 && y < 5) continue;

                double r = getHashNoise(x, y);

                if (biome == 2) {
                    if (r < 0.025) addObject("Snow Tree", x, y);
                    else if (r < 0.035) addObject("Snow Rock", x, y);
                } else if (biome == 1) {
                    if (r < 0.008) addObject("Palm Tree", x, y);
                    else if (r < 0.018) addObject("Sand Rock", x, y);
                    else if (r < 0.035) addObject("Cactus", x, y);
                } else {
                    if (r < 0.025) addObject("Tree", x, y);
                    else if (r < 0.035) addObject("Stone", x, y);
                    else if (r < 0.045) addObject("Trunk", x, y);
                }
            }
        }
        System.out.println(" [GameService] Generated " + activeObjects.size() + " objects (Hash-Based 64-bit).");
    }

    private void addObject(String type, int x, int y) {
        String key = x + "_" + y;
        activeObjects.put(key, new WorldObject(type, x, y));
    }

    // --- GAME LOOP ---
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
        int bound = MAP_RADIUS - 10;
        for(int i=0; i<3; i++) {
            int rx = ThreadLocalRandom.current().nextInt(-bound, bound);
            int ry = ThreadLocalRandom.current().nextInt(-bound, bound);
            if (rx * rx + ry * ry > bound * bound) continue;

            double worldX = rx * TILE_SIZE + (TILE_SIZE / 2.0);
            double worldY = ry * TILE_SIZE + (TILE_SIZE / 2.0);

            if (!isBlocked(worldX, worldY)) {
                spawnMonster("Slime", (int)worldX, (int)worldY);
                break;
            }
        }
    }

    // --- AI LOGIC (Passive until Attacked) ---
    private void updateMonsterAI(Monster m, long now) {
        PlayerState target = null;

        // 1. Only get target if we ALREADY have an ID (set via being hit)
        if (m.targetPlayerId != null) {
            target = playerStates.get(m.targetPlayerId);
            // If player disconnected, stop chasing
            if (target == null) {
                m.targetPlayerId = null;
                m.isRetreating = false;
                m.state = Monster.State.IDLE;
            }
        }

        // [FIX 2] REMOVED the "Auto-Scan" loop here.
        // Slimes will no longer look for players to attack.
        // They only react if 'target' was set above (i.e., they were hurt).

        if (target != null) {
            double dist = getDistance(m.x, m.y, target.getX(), target.getY());

            // Give up if too far
            if (dist > CHASE_STOP_DIST) {
                m.targetPlayerId = null;
                m.isRetreating = false;
                m.state = Monster.State.IDLE;
                return;
            }

            // Retreat Logic (Low HP)
            if (m.hp <= 3 && !m.isRetreating && m.hp > 0 && ThreadLocalRandom.current().nextDouble() < 0.02) {
                m.isRetreating = true;
                m.stateTimer = now + 3000;
            }

            if (m.isRetreating) {
                m.state = Monster.State.RETREAT;
                if (now > m.stateTimer) m.isRetreating = false;
                else moveMonsterSmart(m, Math.atan2(m.y - target.getY(), m.x - target.getX()));
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
                moveMonsterSmart(m, Math.atan2(target.getY() - m.y, target.getX() - m.x));
            }
        }
        else {
            // No Target -> Wander Peacefully
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
                m.dx = Math.cos(wanderAngle) * (m.speed * 0.5); // Slower wander speed
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
                // Hit a wall while wandering -> Stop and wait
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

    // --- PLAYER MOVEMENT: SLIDING + FORGIVING RADIUS ---
    public PlayerState processMove(String sessionId, double requestedX, double requestedY, long seqId) {
        PlayerState player = playerStates.get(sessionId);
        if (player != null) {
            // 1. Try full move
            if (isValidPosition(requestedX, requestedY) && !isPlayerBlocked(requestedX, requestedY)) {
                player.setX(requestedX);
                player.setY(requestedY);
            }
            // 2. Slide X
            else if (isValidPosition(requestedX, player.getY()) && !isPlayerBlocked(requestedX, player.getY())) {
                player.setX(requestedX);
            }
            // 3. Slide Y
            else if (isValidPosition(player.getX(), requestedY) && !isPlayerBlocked(player.getX(), requestedY)) {
                player.setY(requestedY);
            }
            player.setLastProcessedSeqId(seqId);
        }
        return player;
    }

    // --- INTERACTION: AGGRO ON HIT ---
    public WorldObject processInteraction(String sessionId, int targetX, int targetY) {
        PlayerState player = playerStates.get(sessionId);
        if (player == null) return null;
        double playerX = player.getX();
        double playerY = player.getY();

        double hitCenterX = (targetX * TILE_SIZE) + (TILE_SIZE / 2.0);
        double hitCenterY = (targetY * TILE_SIZE) + (TILE_SIZE / 2.0);

        // A. Check Monsters
        for (Monster m : activeMonsters.values()) {
            boolean hit = getDistance(hitCenterX, hitCenterY, m.x, m.y) < 64;
            if (hit) {
                m.hp--;
                // [FIX 2] Set Aggro here!
                m.targetPlayerId = sessionId;
                m.state = Monster.State.HURT;
                m.stateTimer = System.currentTimeMillis() + 400;

                double angle = Math.atan2(m.y - playerY, m.x - playerX);
                double knX = m.x + Math.cos(angle) * 15.0;
                double knY = m.y + Math.sin(angle) * 15.0;
                if(!isBlocked(knX, knY)) { m.x = (int)knX; m.y = (int)knY; }

                // [FIX 3] Death Logic
                if (m.hp <= 0) {
                    activeMonsters.remove(m.id);
                    System.out.println("Monster Died: " + m.id);
                    return m; // Returning m allows Handler to send death packet
                }
                return m;
            }
        }

        // B. Check Objects (Fuzzy)
        String directKey = targetX + "_" + targetY;
        WorldObject targetObj = activeObjects.get(directKey);

        if (targetObj == null) {
            double minDst = Double.MAX_VALUE;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    String k = (targetX + dx) + "_" + (targetY + dy);
                    WorldObject candidate = activeObjects.get(k);
                    if (candidate != null) {
                        double ox = candidate.x * TILE_SIZE + (TILE_SIZE/2.0);
                        double oy = candidate.y * TILE_SIZE + (TILE_SIZE/2.0);
                        double d = getDistance(hitCenterX, hitCenterY, ox, oy);
                        if (d < 96 && d < minDst) { minDst = d; targetObj = candidate; directKey = k; }
                    }
                }
            }
        }

        if (targetObj == null) return null;

        targetObj.hp--;
        if (targetObj.hp <= 0) {
            destroyedObjectIds.add(directKey);
            activeObjects.remove(directKey);
        }
        return targetObj;
    }

    private boolean isValidPosition(double x, double y) {
        int tx = (int) Math.floor(x / TILE_SIZE);
        int ty = (int) Math.floor(y / TILE_SIZE);
        return getTerrainAt(tx, ty) != -1;
    }

    // Use reduced radius for more forgiving movement
    private boolean isPlayerBlocked(double x, double y) {
        if (isBlocked(x, y)) return true;
        if (isBlocked(x + PLAYER_RADIUS, y)) return true;
        if (isBlocked(x - PLAYER_RADIUS, y)) return true;
        if (isBlocked(x, y + PLAYER_RADIUS)) return true;
        if (isBlocked(x, y - PLAYER_RADIUS)) return true;
        return false;
    }

    private boolean isBlocked(double x, double y) {
        int tileX = (int) Math.floor(x / 64.0);
        int tileY = (int) Math.floor(y / 64.0);
        if (getTerrainAt(tileX, tileY) == -1) return true;
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
        m.id = id; activeMonsters.put(id, m);
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
                player.removeItem("Wood", 3); player.removeItem("Stone", 2); player.removeItem("Rope", 1);
                player.addItem("Pickaxe", 1); return true;
            }
        }
        else if (recipe.equals("Bonfire")) {
            if (player.hasItem("Wood", 10) && player.hasItem("Stone", 5)) {
                player.removeItem("Wood", 10); player.removeItem("Stone", 5);
                player.addItem("Bonfire", 1); return true;
            }
        }
        return false;
    }
    public boolean processPlaceObject(String sessionId, String type, int x, int y) {
        PlayerState player = playerStates.get(sessionId);
        if (player == null || !player.hasItem(type, 1)) return false;
        if (getTerrainAt(x, y) == -1) return false;
        String objKey = x + "_" + y;
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
                activeObjects.remove(objKey); destroyedObjectIds.add(objKey); return true;
            }
        }
        return false;
    }

    public List<DropResult> calculateDrops(String type, boolean destroyed) {
        List<DropResult> drops = new ArrayList<>();
        if (type.equals("Slime")) { drops.add(new DropResult("Rope", 1)); return drops; }
        if (type.equals("Crafting Table")) { drops.add(new DropResult("Crafting Table", 1)); return drops; }
        if (type.equals("Bonfire")) { drops.add(new DropResult("Bonfire", 1)); return drops; }

        int amount = destroyed ? ThreadLocalRandom.current().nextInt(3, 6) : 1;
        if (type.equals("Tree") || type.equals("Snow Tree") || type.equals("Palm Tree")) drops.add(new DropResult("Wood", amount));
        else if (type.equals("Stone") || type.equals("Snow Rock") || type.equals("Sand Rock")) drops.add(new DropResult("Stone", amount));
        else if (type.equals("Cactus")) drops.add(new DropResult("Wood", 1));
        else if (type.equals("Trunk")) { drops.add(new DropResult("Wood", amount)); drops.add(new DropResult("Rope", amount)); }
        return drops;
    }

    public static class DropResult {
        public String type; public int amount;
        public DropResult(String t, int a) { type = t; amount = a; }
    }
}