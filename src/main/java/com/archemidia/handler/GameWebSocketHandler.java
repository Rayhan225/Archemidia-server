package com.archemidia.handler;

import com.archemidia.model.Monster;
import com.archemidia.model.PlayerState;
import com.archemidia.model.WorldObject;
import com.archemidia.service.GameService;
import com.archemidia.service.TimeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    private final GameService gameService;
    private final TimeService timeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final List<WebSocketSession> activeSessions = new CopyOnWriteArrayList<>();

    public GameWebSocketHandler(GameService gameService, TimeService timeService) {
        this.gameService = gameService;
        this.timeService = timeService;
    }

    // --- GAME LOOP BROADCAST (50ms / 20 TPS) ---
    @Scheduled(fixedRate = 50)
    public void broadcastGameLoop() {
        if (activeSessions.isEmpty()) return;

        for (WebSocketSession session : activeSessions) {
            if (session.isOpen()) {
                PlayerState p = gameService.getPlayer(session.getId());
                if (p != null) {
                    try {
                        sendWorldUpdate(session, p);
                    } catch (IOException e) {
                        // Handle disconnects gracefully
                    }
                }
            }
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        activeSessions.add(session);
        gameService.onPlayerConnect(session.getId());

        // Send initial state
        sendInventoryUpdate(session, gameService.getPlayer(session.getId()));
        sendAllActiveObjects(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        activeSessions.remove(session);
        gameService.onPlayerDisconnect(session.getId());
    }

    private void sendAllActiveObjects(WebSocketSession session) throws IOException {
        Map<String, WorldObject> objects = gameService.getActiveObjects();
        if (objects.isEmpty()) return;

        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("event", "position_update");
        ArrayNode arr = msg.putArray("objects");

        for (WorldObject obj : objects.values()) {
            ObjectNode n = arr.addObject();
            n.put("type", obj.type);
            n.put("x", obj.x);
            n.put("y", obj.y);
        }

        // FIX: Synchronize write
        synchronized (session) {
            session.sendMessage(new TextMessage(msg.toString()));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode json = objectMapper.readTree(message.getPayload());
            String action = json.get("action").asText();
            String sessionId = session.getId();

            if ("request_move".equals(action)) {
                gameService.processMove(sessionId, json.get("x").asDouble(), json.get("y").asDouble(), json.get("seqId").asLong());
            }
            else if ("collect_item".equals(action)) {
                String itemType = json.get("item").asText();
                PlayerState p = gameService.processPickup(sessionId, itemType);
                sendInventoryUpdate(session, p);
            }
            else if ("remove_item".equals(action)) {
                String itemType = json.get("item").asText();
                int amount = json.has("amount") ? json.get("amount").asInt() : 1;
                PlayerState p = gameService.processRemoveItem(sessionId, itemType, amount);
                sendInventoryUpdate(session, p);
            }
            // --- PLAYER DROPPING ITEMS (Pressed X) ---
            else if ("drop_item".equals(action)) {
                String itemType = json.get("item").asText();
                GameService.DropResult result = gameService.dropItem(sessionId, itemType);

                if (result != null) {
                    PlayerState p = gameService.getPlayer(sessionId);
                    sendInventoryUpdate(session, p);

                    // Broadcast visual drop to all players
                    ObjectNode dropMsg = objectMapper.createObjectNode();
                    dropMsg.put("event", "item_spawn");
                    dropMsg.put("x", p.getX());
                    dropMsg.put("y", p.getY());

                    ArrayNode dropsNode = dropMsg.putArray("drops");
                    ObjectNode dNode = dropsNode.addObject();
                    dNode.put("type", result.type);
                    dNode.put("amount", result.amount);

                    TextMessage tm = new TextMessage(dropMsg.toString());
                    broadcastToAll(tm);
                }
            }
            else if ("craft_item".equals(action)) {
                String recipe = json.get("recipe").asText();
                if (gameService.processCrafting(sessionId, recipe)) {
                    sendInventoryUpdate(session, gameService.getPlayer(sessionId));
                }
            }
            // --- PLACING BUILDINGS ---
            else if ("place_object".equals(action)) {
                String type = json.get("type").asText();
                int x = json.get("x").asInt();
                int y = json.get("y").asInt();
                if (gameService.processPlaceObject(sessionId, type, x, y)) {
                    ObjectNode msg = objectMapper.createObjectNode();
                    msg.put("event", "object_placed");
                    msg.put("type", type);
                    msg.put("x", x);
                    msg.put("y", y);

                    TextMessage tm = new TextMessage(msg.toString());
                    broadcastToAll(tm);

                    sendInventoryUpdate(session, gameService.getPlayer(sessionId));
                }
            }
            // --- PICKING UP BUILDINGS ---
            else if ("pickup_object".equals(action)) {
                int x = json.get("x").asInt();
                int y = json.get("y").asInt();

                // 1. Peek at object
                WorldObject obj = gameService.getActiveObjects().get(x + "_" + y);
                String typeToDrop = (obj != null) ? obj.type : "Crafting Table";

                // 2. Process removal
                if (gameService.processPickupObject(sessionId, x, y)) {
                    ObjectNode msg = objectMapper.createObjectNode();
                    msg.put("event", "object_removed");
                    msg.put("x", x);
                    msg.put("y", y);

                    ArrayNode dropsNode = msg.putArray("drops");
                    ObjectNode dNode = dropsNode.addObject();
                    dNode.put("type", typeToDrop);
                    dNode.put("amount", 1);

                    TextMessage tm = new TextMessage(msg.toString());
                    broadcastToAll(tm);

                    sendInventoryUpdate(session, gameService.getPlayer(sessionId));
                }
            }
            // --- INTERACTION / COMBAT ---
            else if ("interact".equals(action)) {
                int tx = json.get("x").asInt();
                int ty = json.get("y").asInt();

                WorldObject obj = gameService.processInteraction(sessionId, tx, ty);

                if (obj != null) {
                    ObjectNode msg = objectMapper.createObjectNode();
                    msg.put("x", tx);
                    msg.put("y", ty);

                    boolean destroyed = obj.hp <= 0;
                    List<GameService.DropResult> drops = gameService.calculateDrops(obj.type, destroyed);

                    if (obj instanceof Monster) {
                        msg.put("event", "monster_hit");
                        msg.put("id", obj.id);
                        msg.put("hp", obj.hp);
                        if(destroyed) msg.put("destroyed", true);
                    } else {
                        if (destroyed) msg.put("event", "object_removed");
                        else {
                            msg.put("event", "object_hit");
                            msg.put("hp", obj.hp);
                        }
                    }

                    ArrayNode dropsNode = msg.putArray("drops");
                    for(GameService.DropResult d : drops) {
                        ObjectNode dNode = dropsNode.addObject();
                        dNode.put("type", d.type);
                        dNode.put("amount", d.amount);
                    }

                    TextMessage tm = new TextMessage(msg.toString());
                    broadcastToAll(tm);
                }
            }
        } catch (Exception e) {
            System.err.println("WS Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void broadcastToAll(TextMessage message) throws IOException {
        for(WebSocketSession s : activeSessions) {
            if(s.isOpen()) {
                // FIX: Synchronize write to prevent TEXT_PARTIAL_WRITING error
                synchronized (s) {
                    s.sendMessage(message);
                }
            }
        }
    }

    private void sendWorldUpdate(WebSocketSession session, PlayerState state) throws IOException {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("event", "position_update");
        response.put("x", state.getX());
        response.put("y", state.getY());
        response.put("time", timeService.getGameTime());
        response.put("hp", state.getHp());
        response.put("maxHp", state.getMaxHp());

        ArrayNode monsters = response.putArray("monsters");
        Map<String, Monster> active = gameService.getActiveMonsters();

        for (Monster m : active.values()) {
            if (Math.abs(m.x - state.getX()) < 1000 && Math.abs(m.y - state.getY()) < 1000) {
                ObjectNode mNode = monsters.addObject();
                mNode.put("id", m.id);
                mNode.put("x", m.x);
                mNode.put("y", m.y);
                mNode.put("hp", m.hp);
                mNode.put("state", m.state.toString());
            }
        }

        // FIX: Synchronize write
        synchronized (session) {
            session.sendMessage(new TextMessage(response.toString()));
        }
    }

    private void sendInventoryUpdate(WebSocketSession session, PlayerState p) throws IOException {
        if (p != null) {
            ObjectNode invMsg = objectMapper.createObjectNode();
            invMsg.put("event", "inventory_update");
            invMsg.putPOJO("items", p.getInventory());

            // FIX: Synchronize write
            synchronized (session) {
                session.sendMessage(new TextMessage(invMsg.toString()));
            }
        }
    }
}