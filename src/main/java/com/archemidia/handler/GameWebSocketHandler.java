package com.archemidia.handler;

import com.archemidia.model.Monster;
import com.archemidia.model.PlayerState;
import com.archemidia.model.WorldObject;
import com.archemidia.model.minigames.TicTacToeGame;
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
                        // Log error or handle disconnection if needed
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

        // We still send this initially for fast reloads, but the client
        // can now request it again manually when the scene changes.
        sendAllActiveObjects(session);

        sendFriendListUpdate(session.getId());
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
        msg.put("event", "position_update"); // Reusing event handler in client
        ArrayNode arr = msg.putArray("objects");

        for (WorldObject obj : objects.values()) {
            ObjectNode n = arr.addObject();
            n.put("type", obj.type);
            n.put("x", obj.x);
            n.put("y", obj.y);
            // Add extra data for farming/crops
            if (obj.type.equals("Farmland")) {
                n.put("cropType", "Turnip");
                n.put("cropStage", 0);
            }
        }

        synchronized (session) {
            session.sendMessage(new TextMessage(msg.toString()));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode json = objectMapper.readTree(message.getPayload());
            String action = json.has("action") ? json.get("action").asText() : "";
            String sessionId = session.getId();

            // --- [FIX] NEW: Request World Objects manually ---
            if ("request_world_objects".equals(action)) {
                sendAllActiveObjects(session);
            }
            // --- UPDATED: PROFILE SYSTEM (Name + Avatar) ---
else if ("set_profile".equals(action)) {
                String desiredName = json.has("name") ? json.get("name").asText() : "";
                int desiredAvatar = json.has("avatar") ? json.get("avatar").asInt() : 0;

                // Register Name
                boolean nameSuccess = true;
                if (!desiredName.isEmpty()) {
                    nameSuccess = gameService.registerName(sessionId, desiredName);
                }

                // Update Avatar
                PlayerState p = gameService.getPlayer(sessionId);
                if (p != null) {
                    p.setAvatarId(desiredAvatar);
                }

                // Send Response
                ObjectNode resp = objectMapper.createObjectNode();
                resp.put("event", "profile_update_result");
                resp.put("success", nameSuccess);
                resp.put("name", (p != null) ? p.getName() : "Guest");
                resp.put("avatar", desiredAvatar);

                synchronized (session) { session.sendMessage(new TextMessage(resp.toString())); }

                if (nameSuccess && !desiredName.isEmpty()) {
                    broadcastChat("System", "SYSTEM", (p != null ? p.getName() : desiredName) + " updated their profile.");
                }
            }

            // --- CORE MOVEMENT ---
            else if ("request_move".equals(action)) {
                gameService.processMove(sessionId, json.get("x").asDouble(), json.get("y").asDouble(), json.get("seqId").asLong());
            }

            // --- SET NAME ---
            else if ("set_name".equals(action)) {
                String desiredName = json.get("name").asText();
                boolean success = gameService.registerName(sessionId, desiredName);

                ObjectNode resp = objectMapper.createObjectNode();
                resp.put("event", "name_set_result");
                resp.put("success", success);
                resp.put("name", success ? desiredName : "Guest");

                synchronized (session) { session.sendMessage(new TextMessage(resp.toString())); }

                if (success) {
                    broadcastChat("System", "SYSTEM", desiredName + " has joined the world.");
                }
            }

            // --- INVENTORY / ITEMS ---
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
            else if ("drop_item".equals(action)) {
                String itemType = json.get("item").asText();
                GameService.DropResult result = gameService.dropItem(sessionId, itemType);

                if (result != null) {
                    PlayerState p = gameService.getPlayer(sessionId);
                    sendInventoryUpdate(session, p);

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

            // --- WORLD OBJECTS (Placing/Removing) ---
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
            else if ("pickup_object".equals(action)) {
                int x = json.get("x").asInt();
                int y = json.get("y").asInt();
                WorldObject obj = gameService.getActiveObjects().get(x + "_" + y);
                String typeToDrop = (obj != null) ? obj.type : "Crafting Table";

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

            // --- MINIGAME: PARTY MODE ---
            else if ("start_party".equals(action)) {
                sendJson(session, "party_update", gameService.startParty(sessionId));
            }
            else if ("party_hit".equals(action)) {
                sendJson(session, "party_update", gameService.hitParty(sessionId));
            }
            else if ("party_miss".equals(action)) {
                sendJson(session, "party_update", gameService.missParty(sessionId));
            }

            // --- MINIGAME: TIC-TAC-TOE ---
            else if ("start_ttt".equals(action)) {
                TicTacToeGame game = gameService.startTicTacToe(sessionId);
                sendTTTUpdate(session, game);
            }
            else if ("move_ttt".equals(action)) {
                int index = json.get("index").asInt();
                TicTacToeGame game = gameService.processTicTacToeMove(sessionId, index);
                if (game != null) sendTTTUpdate(session, game);
            }
            else if ("reset_ttt".equals(action)) {
                TicTacToeGame game = gameService.resetTicTacToe(sessionId);
                if (game != null) sendTTTUpdate(session, game);
            }

            // --- GAME HUB ---
            else if ("open_hub".equals(action)) {
                if (gameService.tryOpenHub(sessionId)) {
                    ObjectNode msg = objectMapper.createObjectNode();
                    msg.put("event", "open_window");
                    msg.put("window", "game_hub");
                    synchronized (session) {
                        session.sendMessage(new TextMessage(msg.toString()));
                    }
                }
            }

            // --- GENERIC INTERACTION (Spacebar / Farming / Combat) ---
            else if ("interact".equals(action)) {
                int tx = json.get("x").asInt();
                int ty = json.get("y").asInt();

                GameService.InteractionResult res = gameService.processInteraction(sessionId, tx, ty);

                if (res != null) {
                    if (res.uiOpen != null) {
                        ObjectNode msg = objectMapper.createObjectNode();
                        msg.put("event", "open_window");
                        msg.put("window", res.uiOpen);
                        synchronized (session) {
                            session.sendMessage(new TextMessage(msg.toString()));
                        }
                        return;
                    }

                    WorldObject obj = res.object;
                    ObjectNode msg = objectMapper.createObjectNode();
                    msg.put("x", tx);
                    msg.put("y", ty);

                    if (obj instanceof Monster) {
                        msg.put("event", "monster_hit");
                        msg.put("id", obj.id);
                        msg.put("hp", obj.hp);
                        if(res.destroyed) msg.put("destroyed", true);
                    }
                    else if (res.created) {
                        msg.put("event", "object_placed");
                        msg.put("type", obj.type);
                    }
                    else if (res.destroyed) {
                        msg.put("event", "object_removed");
                    }
                    else {
                        msg.put("event", "object_hit");
                        msg.put("hp", obj.hp);
                    }

                    ArrayNode dropsNode = msg.putArray("drops");
                    if (res.drops != null) {
                        for(GameService.DropResult d : res.drops) {
                            ObjectNode dNode = dropsNode.addObject();
                            dNode.put("type", d.type);
                            dNode.put("amount", d.amount);
                        }
                    }

                    TextMessage tm = new TextMessage(msg.toString());
                    broadcastToAll(tm);
                }
            }

            // --- CHAT SYSTEM ---
            else if ("chat_send".equals(action)) {
                String type = json.get("type").asText(); // "GLOBAL" or "PRIVATE"
                String msgContent = json.get("message").asText();
                PlayerState sender = gameService.getPlayer(sessionId);
                String senderName = (sender != null) ? sender.getName() : "Unknown";

                if (type.equals("GLOBAL")) {
                    broadcastChat(sessionId, "[Global]", senderName + ": " + msgContent);
                }
                else if (type.equals("PRIVATE")) {
                    String targetName = json.get("target").asText();
                    String targetSessionId = gameService.getSessionByName(targetName);

                    if (targetSessionId != null) {
                        sendPrivateChat(sessionId, targetSessionId, msgContent);
                    } else {
                        sendSystemMessage(sessionId, "User '" + targetName + "' not found.");
                    }
                }
            }

            // --- FRIEND SYSTEM ---
            else if ("friend_request".equals(action)) {
                String targetName = json.get("targetName").asText();
                String targetSessionId = gameService.getSessionByName(targetName);

                PlayerState sender = gameService.getPlayer(sessionId);

                if (targetSessionId != null && sender != null) {
                    PlayerState target = gameService.getPlayer(targetSessionId);

                    target.addFriendRequest(sender.getName());
                    sendSystemMessage(targetSessionId, "Friend Request from: " + sender.getName());
                    sendFriendListUpdate(targetSessionId);

                    sendSystemMessage(sessionId, "Request sent to " + targetName);
                } else {
                    sendSystemMessage(sessionId, "Player '" + targetName + "' not found.");
                }
            }
            else if ("friend_accept".equals(action)) {
                String requesterName = json.get("targetName").asText();
                String requesterSessionId = gameService.getSessionByName(requesterName);

                PlayerState receiver = gameService.getPlayer(sessionId);

                if (receiver != null && receiver.getFriendRequests().contains(requesterName)) {
                    receiver.removeFriendRequest(requesterName);
                    receiver.addFriend(requesterName);

                    if (requesterSessionId != null) {
                        PlayerState requester = gameService.getPlayer(requesterSessionId);
                        requester.addFriend(receiver.getName());
                        sendSystemMessage(requesterSessionId, receiver.getName() + " accepted your friend request!");
                        sendFriendListUpdate(requesterSessionId);
                    }

                    sendSystemMessage(sessionId, "You are now friends with " + requesterName);
                    sendFriendListUpdate(sessionId);
                }
            }

        } catch (Exception e) {
            System.err.println("WS Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // --- HELPER METHODS ---

    private void broadcastToAll(TextMessage message) throws IOException {
        for(WebSocketSession s : activeSessions) {
            if(s.isOpen()) {
                synchronized (s) {
                    s.sendMessage(message);
                }
            }
        }
    }

    private void broadcastChat(String senderId, String channel, String message) throws IOException {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("event", "chat_message");
        msg.put("type", "GLOBAL");
        msg.put("sender", senderId);
        msg.put("text", message);

        TextMessage tm = new TextMessage(msg.toString());
        broadcastToAll(tm);
    }

    private void sendPrivateChat(String senderId, String targetId, String message) throws IOException {
        PlayerState sender = gameService.getPlayer(senderId);
        PlayerState target = gameService.getPlayer(targetId);

        String sName = (sender != null) ? sender.getName() : "???";
        String tName = (target != null) ? target.getName() : "???";

        // 1. Send to Target
        WebSocketSession targetSession = null;
        for (WebSocketSession s : activeSessions) {
            if (s.getId().equals(targetId)) {
                targetSession = s;
                break;
            }
        }

        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("event", "chat_message");
        msg.put("type", "PRIVATE");
        msg.put("sender", sName); // "From"
        msg.put("text", message);

        if (targetSession != null && targetSession.isOpen()) {
            synchronized (targetSession) {
                targetSession.sendMessage(new TextMessage(msg.toString()));
            }
        }

        // 2. Echo back to Sender
        WebSocketSession senderSession = null;
        for (WebSocketSession s : activeSessions) {
            if (s.getId().equals(senderId)) {
                senderSession = s;
                break;
            }
        }

        if (senderSession != null) {
            ObjectNode echo = msg.deepCopy();
            echo.put("sender", "You -> " + tName);
            synchronized (senderSession) {
                senderSession.sendMessage(new TextMessage(echo.toString()));
            }
        }
    }

    private void sendSystemMessage(String sessionId, String text) throws IOException {
        for (WebSocketSession s : activeSessions) {
            if (s.getId().equals(sessionId)) {
                ObjectNode msg = objectMapper.createObjectNode();
                msg.put("event", "chat_message");
                msg.put("type", "SYSTEM");
                msg.put("sender", "System");
                msg.put("text", text);
                synchronized (s) { s.sendMessage(new TextMessage(msg.toString())); }
                break;
            }
        }
    }

    private void sendFriendListUpdate(String sessionId) throws IOException {
        PlayerState p = gameService.getPlayer(sessionId);
        if (p == null) return;

        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("event", "friend_update");
        ArrayNode friendsNode = msg.putArray("friends");
        for (String f : p.getFriends()) friendsNode.add(f);

        ArrayNode reqNode = msg.putArray("requests");
        for (String r : p.getFriendRequests()) reqNode.add(r);

        for (WebSocketSession s : activeSessions) {
            if (s.getId().equals(sessionId)) {
                synchronized (s) { s.sendMessage(new TextMessage(msg.toString())); }
                break;
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

        // --- 1. MULTIPLAYER VISIBILITY LOGIC ---
        ArrayNode playersNode = response.putArray("players");
        for (PlayerState other : gameService.getOnlinePlayers()) {
            if (other.getPlayerId().equals(session.getId())) continue;

            if (Math.abs(other.getX() - state.getX()) < 1280 && Math.abs(other.getY() - state.getY()) < 1280) {
                ObjectNode pNode = playersNode.addObject();
                pNode.put("id", other.getPlayerId());
                pNode.put("name", other.getName()); // Added Name
                pNode.put("avatar", other.getAvatarId());
                pNode.put("x", other.getX());
                pNode.put("y", other.getY());
                pNode.put("dir", other.getFacingDirection());
            }
        }

        // --- 2. MONSTERS LOGIC ---
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

        synchronized (session) {
            session.sendMessage(new TextMessage(response.toString()));
        }
    }

    private void sendInventoryUpdate(WebSocketSession session, PlayerState p) throws IOException {
        if (p != null) {
            ObjectNode invMsg = objectMapper.createObjectNode();
            invMsg.put("event", "inventory_update");
            invMsg.putPOJO("items", p.getInventoryAsMap());

            synchronized (session) {
                session.sendMessage(new TextMessage(invMsg.toString()));
            }
        }
    }

    private void sendTTTUpdate(WebSocketSession session, TicTacToeGame game) throws IOException {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("event", "ttt_update");
        ArrayNode boardNode = msg.putArray("board");
        for (int cell : game.getBoard()) boardNode.add(cell);

        if (game.gameOver) {
            msg.put("winner", game.winner);
        }

        synchronized (session) {
            session.sendMessage(new TextMessage(msg.toString()));
        }
    }

    private void sendJson(WebSocketSession s, String event, Object data) throws IOException {
        if (data == null) return;
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("event", event);
        msg.putPOJO("data", data);
        synchronized(s) {
            s.sendMessage(new TextMessage(msg.toString()));
        }
    }
}