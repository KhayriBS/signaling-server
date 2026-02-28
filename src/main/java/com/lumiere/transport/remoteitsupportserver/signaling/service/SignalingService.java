package com.lumiere.transport.remoteitsupportserver.signaling.service;

import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SignalingService {
    // sessionId -> (role -> wsSession)
    private final Map<String, Map<String, WebSocketSession>> rooms = new ConcurrentHashMap<>();

    public void register(String sessionId, String role, WebSocketSession ws) {
        rooms.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).put(role, ws);
    }

    public WebSocketSession getPeer(String sessionId, String role) {
        Map<String, WebSocketSession> room = rooms.get(sessionId);
        if (room == null) return null;
        return room.get(role);
    }

    public void remove(String sessionId, WebSocketSession ws) {
        Map<String, WebSocketSession> room = rooms.get(sessionId);
        if (room == null) return;

        room.entrySet().removeIf(e -> e.getValue().getId().equals(ws.getId()));

        if (room.isEmpty()) {
            rooms.remove(sessionId);
        }
    }
}
