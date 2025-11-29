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
                        // Ignore
                    }
                }
            }
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        activeSessions.add(session);
        gameService.onPlayerConnect(session.getId());
        sendInventoryUpdate(session, gameService.getPlayer(session.getId()));

        // --- FIX: Send All Active Objects on Connect (Fixes disappearance bug) ---
        sendAllActiveObjects(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        activeSessions.remove(session);
        gameService.onPlayerDisconnect(session.getId());
    }

    // New Method to sync buildings
    private void sendAllActiveObjects(WebSocketSession session) throws IOException {
        Map<String, WorldObject> objects = gameService.getActiveObjects();
        if (objects.isEmpty()) return;

        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("event", "position_update"); // Reuse update handler or create new one
        ArrayNode arr = msg.putArray("objects");

        for (WorldObject obj : objects.values()) {
            ObjectNode n = arr.addObject();
            n.put("type", obj.type);
            n.put("x", obj.x);
            n.put("y", obj.y);
        }

        session.sendMessage(new TextMessage(msg.toString()));
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
            else if ("craft_item".equals(action)) {
                String recipe = json.get("recipe").asText();
                if (gameService.processCrafting(sessionId, recipe)) {
                    sendInventoryUpdate(session, gameService.getPlayer(sessionId));
                }
            }
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

                    // Broadcast placement to ALL players so they see it instantly
                    TextMessage tm = new TextMessage(msg.toString());
                    for(WebSocketSession s : activeSessions) {
                        if(s.isOpen()) s.sendMessage(tm);
                    }

                    sendInventoryUpdate(session, gameService.getPlayer(sessionId));
                }
            }
            else if ("pickup_object".equals(action)) {
                int x = json.get("x").asInt();
                int y = json.get("y").asInt();
                if (gameService.processPickupObject(sessionId, x, y)) {
                    ObjectNode msg = objectMapper.createObjectNode();
                    msg.put("event", "object_removed");
                    msg.put("x", x);
                    msg.put("y", y);
                    ArrayNode dropsNode = msg.putArray("drops");
                    ObjectNode dNode = dropsNode.addObject();
                    // Determine drop type based on what was there?
                    // For now assuming Crafting Table logic or generic
                    // Ideally GameService should return the object type picked up
                    dNode.put("type", "Crafting Table");
                    dNode.put("amount", 1);

                    TextMessage tm = new TextMessage(msg.toString());
                    for(WebSocketSession s : activeSessions) {
                        if(s.isOpen()) s.sendMessage(tm);
                    }

                    sendInventoryUpdate(session, gameService.getPlayer(sessionId));
                }
            }
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

                    // Broadcast interactions (chops, attacks) to all
                    TextMessage tm = new TextMessage(msg.toString());
                    for(WebSocketSession s : activeSessions) {
                        if(s.isOpen()) s.sendMessage(tm);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
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

        session.sendMessage(new TextMessage(response.toString()));
    }

    private void sendInventoryUpdate(WebSocketSession session, PlayerState p) throws IOException {
        if (p != null) {
            ObjectNode invMsg = objectMapper.createObjectNode();
            invMsg.put("event", "inventory_update");
            invMsg.putPOJO("items", p.getInventory());
            session.sendMessage(new TextMessage(invMsg.toString()));
        }
    }
}