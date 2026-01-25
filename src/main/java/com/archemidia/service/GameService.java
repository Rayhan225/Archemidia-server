package com.archemidia.service;

import com.archemidia.model.Monster;
import com.archemidia.model.PlayerState;
import com.archemidia.model.minigames.TicTacToeGame;
import com.archemidia.model.WorldObject;
import com.archemidia.model.item.Item;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.archemidia.model.minigames.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

import com.archemidia.model.minigames.PartyGame;

@Service
public class GameService {

    private final Map<String, PlayerState> playerStates = new ConcurrentHashMap<>();
    private final Map<String, WorldObject> activeObjects = new ConcurrentHashMap<>();
    private final Map<String, Monster> activeMonsters = new ConcurrentHashMap<>();
    private final List<String> destroyedObjectIds = new ArrayList<>();
    private final Queue<PlayerState> offlinePlayers = new ConcurrentLinkedQueue<>();
    private final Map<String, TicTacToeGame> tttSessions = new ConcurrentHashMap<>();
    private final Map<String, String> nameToSessionMap = new ConcurrentHashMap<>();

    private final PersistenceService persistenceService;
    private final ItemRegistry itemRegistry;

    private final int TILE_SIZE = 64;
    private final double PLAYER_RADIUS = 5.0;
    private final int MAX_MONSTERS = 10;
    private final double CHASE_STOP_DIST = 10 * 64.0;
    private final double ATTACK_RANGE = 30.0;
    private final long ATTACK_COOLDOWN = 1000;
    private final long DASH_COOLDOWN = 3000;
    private final long JUMP_COOLDOWN = 4000;
    private final long EVADE_COOLDOWN = 2000;

    public static final int MAP_RADIUS = 100;
    private final int SNOW_LIMIT = -30;
    private final int SAND_LIMIT = 30;


    public GameService(PersistenceService persistenceService, ItemRegistry itemRegistry) {
        this.persistenceService = persistenceService;
        this.itemRegistry = itemRegistry;
    }

    @PostConstruct
    public void init() {
        PersistenceService.SaveData data = persistenceService.loadData();
        if (data != null) {
            if (data.objects != null) this.activeObjects.putAll(data.objects);
            if (data.monsters != null) this.activeMonsters.putAll(data.monsters);
            if (data.players != null) {
                this.offlinePlayers.addAll(data.players.values());
            }
            System.out.println(" [GameService] Loaded " + activeObjects.size() + " objects, " + activeMonsters.size() + " monsters, " + offlinePlayers.size() + " saved players.");
        }

        if (activeObjects.isEmpty()) {
            System.out.println(" [GameService] World is empty. Generating new terrain...");
            initializeFixedMap();
        }
    }

    @PreDestroy
    public void cleanup() {
        persistenceService.saveData(activeObjects, collectAllPlayersForSave(), activeMonsters);
    }

    @Scheduled(fixedRate = 30000)
    public void autoSave() {
        persistenceService.saveData(activeObjects, collectAllPlayersForSave(), activeMonsters);
    }

    public boolean registerName(String sessionId, String requestedName) {
        if (requestedName == null || requestedName.trim().isEmpty()) return false;
        for (String existingName : nameToSessionMap.keySet()) {
            if (existingName.equalsIgnoreCase(requestedName)) return false;
        }

        PlayerState p = playerStates.get(sessionId);
        if (p != null) {
            if (!p.getName().equals("Unknown")) {
                nameToSessionMap.remove(p.getName());
            }

            p.setName(requestedName);
            nameToSessionMap.put(requestedName, sessionId);
            return true;
        }
        return false;
    }

    public void unregisterName(String name) {
        if (name != null) nameToSessionMap.remove(name);
    }

    public String getSessionByName(String name) {
        return nameToSessionMap.get(name);
    }

    private Map<String, PlayerState> collectAllPlayersForSave() {
        Map<String, PlayerState> all = new HashMap<>(playerStates);
        for (PlayerState p : offlinePlayers) {
            all.putIfAbsent(p.getPlayerId(), p);
        }
        return all;
    }

    private final Map<String, PartyGame> partySessions = new ConcurrentHashMap<>();

    public PartyGame startParty(String sessionId) {
        PartyGame game = new PartyGame();
        game.start();
        partySessions.put(sessionId, game);
        return game;
    }

    public PartyGame hitParty(String sessionId) {
        PartyGame game = partySessions.get(sessionId);
        if (game != null) game.hit();
        return game;
    }

    public PartyGame missParty(String sessionId) {
        PartyGame game = partySessions.get(sessionId);
        if (game != null) game.miss();
        return game;
    }

    public TicTacToeGame startTicTacToe(String sessionId) {
        TicTacToeGame game = new TicTacToeGame();
        tttSessions.put(sessionId, game);
        return game;
    }

    public TicTacToeGame processTicTacToeMove(String sessionId, int index) {
        TicTacToeGame game = tttSessions.get(sessionId);
        if (game != null) {
            game.playerMove(index);
        }
        return game;
    }

    public TicTacToeGame resetTicTacToe(String sessionId) {
        TicTacToeGame game = tttSessions.get(sessionId);
        if (game == null) {
            return startTicTacToe(sessionId);
        }
        game.reset();
        return game;
    }

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
                if (x > -5 && x < 5 && y > -5 && y < 5) continue; // Spawn clearing

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
        addObject("Lighthouse", 0, 0);
    }

    private void addObject(String type, int x, int y) {
        String key = x + "_" + y;
        activeObjects.put(key, new WorldObject(type, x, y));
    }

    @Scheduled(fixedRate = 50)
    public void gameLoop() {
        long now = System.currentTimeMillis();
        if (activeMonsters.size() < MAX_MONSTERS) {
            if (ThreadLocalRandom.current().nextDouble() < 0.02) {
                spawnRandomMonster();
            }
        }
        for (Monster monster : activeMonsters.values()) {
            if (monster.hp <= 0) continue;
            updateMonsterAI(monster, now);
        }
    }

    private void spawnRandomMonster() {
        if (playerStates.isEmpty()) return;

        List<PlayerState> players = new ArrayList<>(playerStates.values());
        PlayerState randomPlayer = players.get(ThreadLocalRandom.current().nextInt(players.size()));

        int spawnRadius = 25;
        int minSpawnDist = 10;
        int px = (int) (randomPlayer.getX() / TILE_SIZE);
        int py = (int) (randomPlayer.getY() / TILE_SIZE);

        for(int i=0; i<5; i++) {
            int offsetX = ThreadLocalRandom.current().nextInt(-spawnRadius, spawnRadius);
            int offsetY = ThreadLocalRandom.current().nextInt(-spawnRadius, spawnRadius);
            if (Math.abs(offsetX) < minSpawnDist && Math.abs(offsetY) < minSpawnDist) continue;
            int tx = px + offsetX;
            int ty = py + offsetY;
            if (getTerrainAt(tx, ty) != 0) continue;
            if (tx * tx + ty * ty > MAP_RADIUS * MAP_RADIUS) continue;

            double worldX = tx * TILE_SIZE + (TILE_SIZE / 2.0);
            double worldY = ty * TILE_SIZE + (TILE_SIZE / 2.0);

            if (!isBlocked(worldX, worldY)) {
                spawnMonster("Slime", (int)worldX, (int)worldY);
                break;
            }
        }
    }

    private void updateMonsterAI(Monster m, long now) {
        PlayerState target = null;
        if (m.targetPlayerId != null) {
            target = playerStates.get(m.targetPlayerId);
            if (target == null) {
                m.targetPlayerId = null;
                m.isAggravated = (m.personality == Monster.Personality.AGGRESSIVE);
                m.state = Monster.State.IDLE;
            }
        }

        if (target != null && !m.isAggravated) target = null;

        if (target != null) {
            double dist = getDistance(m.x, m.y, target.getX(), target.getY());

            if (dist > CHASE_STOP_DIST) {
                m.targetPlayerId = null;
                m.isAggravated = (m.personality == Monster.Personality.AGGRESSIVE);
                m.state = Monster.State.IDLE;
            }
            else if (dist <= ATTACK_RANGE) {
                if (now - m.lastAttackTime > ATTACK_COOLDOWN) {
                    m.state = Monster.State.ATTACK;
                    m.lastAttackTime = now;
                    if (m.isAggravated) target.damage(1);
                }
                m.dx = 0; m.dy = 0;
            }
            else {
                m.state = Monster.State.CHASE;
                moveMonsterSmart(m, Math.atan2(target.getY() - m.y, target.getX() - m.x), m.speed);
            }
        }
        else {
            handlePassiveBehavior(m, now);
        }
    }

    private void moveMonsterSmart(Monster m, double angle, double moveSpeed) {
        double newX = m.x + Math.cos(angle) * moveSpeed;
        double newY = m.y + Math.sin(angle) * moveSpeed;
        if (!isBlocked(newX, newY)) {
            m.x = (int)newX;
            m.y = (int)newY;
        } else {
            for (double offset : new double[]{-0.6, 0.6}) {
                double tryAngle = angle + offset;
                double tryX = m.x + Math.cos(tryAngle) * moveSpeed;
                double tryY = m.y + Math.sin(tryAngle) * moveSpeed;
                if (!isBlocked(tryX, tryY)) {
                    m.x = (int)tryX;
                    m.y = (int)tryY;
                    break;
                }
            }
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

    public static class InteractionResult {
        public WorldObject object;
        public boolean created;
        public boolean destroyed;
        public List<DropResult> drops = new ArrayList<>();
        public boolean isHit;
        public String uiOpen;

        public InteractionResult(WorldObject obj) { this.object = obj; }
    }

    public InteractionResult processInteraction(String sessionId, int targetX, int targetY) {
        PlayerState player = playerStates.get(sessionId);
        if (player == null) return null;
        double playerX = player.getX();
        double playerY = player.getY();

        int damage = 1;
        if (player.hasItem("Pickaxe", 1)) {
            damage = 2;
        }
        boolean hasHoe = player.hasItem("Hoe", 1);

        if (hasHoe) {
            targetX = (int) Math.floor(playerX / TILE_SIZE);
            targetY = (int) Math.floor(playerY / TILE_SIZE);
        }

        double hitCenterX = (targetX * TILE_SIZE) + (TILE_SIZE / 2.0);
        double hitCenterY = (targetY * TILE_SIZE) + (TILE_SIZE / 2.0);

        Monster closestMonster = null;
        double minMonDist = 64.0;
        for (Monster m : activeMonsters.values()) {
            double d = getDistance(hitCenterX, hitCenterY, m.x, m.y);
            if (d < minMonDist) {
                minMonDist = d;
                closestMonster = m;
            }
        }

        if (closestMonster != null) {
            handleMonsterHit(closestMonster, sessionId, playerX, playerY, damage);
            InteractionResult res = new InteractionResult(closestMonster);
            res.isHit = true;
            res.destroyed = (closestMonster.hp <= 0);
            if (res.destroyed) res.drops = calculateDrops(closestMonster.type, true);
            return res;
        }

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

        if (targetObj == null && hasHoe) {
            int terrain = getTerrainAt(targetX, targetY);
            if (terrain == 0) {
                WorldObject farm = new WorldObject("Farmland", targetX, targetY);
                farm.hp = 1;
                activeObjects.put(directKey, farm);

                InteractionResult res = new InteractionResult(farm);
                res.created = true;
                return res;
            }
        }

        if (targetObj != null) {
            if (targetObj.type.equals("Lighthouse")) return null;
            if (!targetObj.type.equals("Farmland") && hasHoe) return null;

            targetObj.hp -= damage;
            InteractionResult res = new InteractionResult(targetObj);
            res.isHit = true;

            if (targetObj.hp <= 0) {
                destroyedObjectIds.add(directKey);
                activeObjects.remove(directKey);
                res.destroyed = true;
                res.drops = calculateDrops(targetObj.type, true);
            } else {
                res.drops = calculateDrops(targetObj.type, false);
            }
            return res;
        }
        return null;
    }

    private void handleMonsterHit(Monster m, String sessionId, double attackerX, double attackerY, int damage) {
        m.isAggravated = true;
        m.targetPlayerId = sessionId;
        m.hp -= damage;
        m.state = Monster.State.HURT;
        m.stateTimer = System.currentTimeMillis() + 400;

        double angle = Math.atan2(m.y - attackerY, m.x - attackerX);
        double knX = m.x + Math.cos(angle) * 40.0;
        double knY = m.y + Math.sin(angle) * 40.0;

        if(!isBlocked(knX, knY)) { m.x = (int)knX; m.y = (int)knY; }
        if (m.hp <= 0) {
            activeMonsters.remove(m.id);
        }
    }

    public PlayerState processMove(String sessionId, double requestedX, double requestedY, long seqId) {
        PlayerState player = playerStates.get(sessionId);
        if (player != null) {
            if (isValidPosition(requestedX, requestedY) && !isPlayerBlocked(requestedX, requestedY)) {
                player.setX(requestedX);
                player.setY(requestedY);
            }
            else if (isValidPosition(requestedX, player.getY()) && !isPlayerBlocked(requestedX, player.getY())) {
                player.setX(requestedX);
            }
            else if (isValidPosition(player.getX(), requestedY) && !isPlayerBlocked(player.getX(), requestedY)) {
                player.setY(requestedY);
            }
            player.setLastProcessedSeqId(seqId);
        }
        return player;
    }

    private boolean isValidPosition(double x, double y) {
        int tx = (int) Math.floor(x / TILE_SIZE);
        int ty = (int) Math.floor(y / TILE_SIZE);
        return getTerrainAt(tx, ty) != -1;
    }

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
        WorldObject obj = activeObjects.get(key);
        if (obj != null) {
            if (obj.type.equals("Farmland")) return false;
            return true;
        }
        return false;
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
        PlayerState state;

        if (!offlinePlayers.isEmpty()) {
            state = offlinePlayers.poll();
            String oldId = state.getPlayerId();
            state.setPlayerId(sessionId);
            if (!state.getName().equals("Unknown")) {
                nameToSessionMap.put(state.getName(), sessionId);
            }

            for (WorldObject obj : activeObjects.values()) {
                if (obj.ownerId != null && obj.ownerId.equals(oldId)) {
                    obj.ownerId = sessionId;
                }
            }
            System.out.println(" [GameService] Restored player " + oldId + " -> " + sessionId);
        }
        else {
            state = new PlayerState(sessionId, 0, 150);
            boolean ownsTable = activeObjects.values().stream()
                    .anyMatch(o -> "Crafting Table".equals(o.type) && sessionId.equals(o.ownerId));
            if (!ownsTable) {
                Item table = itemRegistry.getItem("Crafting Table");
                if (table != null) state.addItem(table, 1);
            }
        }
        playerStates.put(sessionId, state);
        return state;
    }

    public void onPlayerDisconnect(String sessionId) {
        PlayerState state = playerStates.remove(sessionId);
        if (state != null) {
            unregisterName(state.getName());
            offlinePlayers.add(state);
            System.out.println(" [GameService] Player " + sessionId + " stored in offline queue.");
        }
    }

    public PlayerState getPlayer(String sessionId) { return playerStates.get(sessionId); }
    public Collection<PlayerState> getOnlinePlayers() { return playerStates.values(); }

    public PlayerState processPickup(String sessionId, String itemType) {
        PlayerState player = playerStates.get(sessionId);
        if (player != null) {
            Item item = itemRegistry.getItem(itemType);
            if (item != null) {
                player.addItem(item, 1);
            }
        }
        return player;
    }

    public PlayerState processRemoveItem(String sessionId, String itemType, int amount) {
        PlayerState player = playerStates.get(sessionId);
        if (player != null) {
            player.removeItem(itemType, amount);
        }
        return player;
    }

    public DropResult dropItem(String sessionId, String itemType) {
        PlayerState player = playerStates.get(sessionId);
        if (player != null && player.hasItem(itemType, 1)) {
            player.removeItem(itemType, 1);
            return new DropResult(itemType, 1);
        }
        return null;
    }

    public boolean processCrafting(String sessionId, String recipe) {
        PlayerState player = playerStates.get(sessionId);
        if (player == null) return false;

        if (recipe.equals("Pickaxe")) {
            if (player.hasItem("Wood", 3) && player.hasItem("Stone", 2) && player.hasItem("Rope", 1)) {
                if (player.hasItem("Pickaxe", 1)) return false;
                player.removeItem("Wood", 3);
                player.removeItem("Stone", 2);
                player.removeItem("Rope", 1);
                player.addItem(itemRegistry.getItem("Pickaxe"), 1);
                return true;
            }
        }
        else if (recipe.equals("Hoe")) {
            if (player.hasItem("Wood", 2) && player.hasItem("Stone", 2) && player.hasItem("Rope", 1)) {
                if (player.hasItem("Hoe", 1)) return false;
                player.removeItem("Wood", 2);
                player.removeItem("Stone", 2);
                player.removeItem("Rope", 1);
                player.addItem(itemRegistry.getItem("Hoe"), 1);
                return true;
            }
        }
        else if (recipe.equals("Bonfire")) {
            if (player.hasItem("Wood", 10) && player.hasItem("Stone", 5)) {
                if (player.hasItem("Bonfire", 10)) return false;
                player.removeItem("Wood", 10);
                player.removeItem("Stone", 5);
                player.addItem(itemRegistry.getItem("Bonfire"), 1);
                return true;
            }
        }
        else if (recipe.equals("Fence")) {
            if (player.hasItem("Wood", 2)) {
                if (player.hasItem("Fence", 100)) return false;
                player.removeItem("Wood", 2);
                player.addItem(itemRegistry.getItem("Fence"), 1);
                return true;
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

        if (type.equals("Crafting Table")) {
            boolean alreadyOwnsTable = activeObjects.values().stream()
                    .anyMatch(o -> "Crafting Table".equals(o.type) && sessionId.equals(o.ownerId));
            if (alreadyOwnsTable) return false;
        }

        WorldObject obj = new WorldObject(type, x, y);
        obj.ownerId = sessionId;
        if (type.equals("Crafting Table") || type.equals("Bonfire")) obj.hp = 3;
        if (type.equals("Fence")) obj.hp = 2;

        activeObjects.put(objKey, obj);
        player.removeItem(type, 1);
        return true;
    }

    public boolean processPickupObject(String sessionId, int x, int y) {
        String objKey = x + "_" + y;
        WorldObject obj = activeObjects.get(objKey);
        if (obj != null) {
            if (obj.type.equals("Lighthouse")) return false;

            if (obj.type.equals("Crafting Table") || obj.type.equals("Bonfire") || obj.type.equals("Fence")) {
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
        if (type.equals("Fence")) { drops.add(new DropResult("Fence", 1)); return drops; }
        if (type.equals("Farmland")) return drops;

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

    public boolean tryOpenHub(String sessionId) {
        PlayerState player = playerStates.get(sessionId);
        if (player == null) return false;
        double dist = Math.sqrt(Math.pow(player.getX(), 2) + Math.pow(player.getY(), 2));
        return dist < (4 * 64.0);
    }
}