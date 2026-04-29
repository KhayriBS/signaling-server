package com.lumiere.transport.remoteitsupportserver.signaling.ws;

import com.lumiere.transport.remoteitsupportserver.session.entity.ControlSession;
import com.lumiere.transport.remoteitsupportserver.session.entity.SessionStatus;
import com.lumiere.transport.remoteitsupportserver.session.repository.ControlSessionRepository;
import com.lumiere.transport.remoteitsupportserver.session.service.SessionService;
import com.lumiere.transport.remoteitsupportserver.signaling.model.SignalMessage;
import com.lumiere.transport.remoteitsupportserver.signaling.model.SignalType;
import com.lumiere.transport.remoteitsupportserver.signaling.service.SignalingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component

public class SignalingWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(SignalingWebSocketHandler.class);
    private static final long SESSION_GRACE_PERIOD_SECONDS = 45;

    private final SignalingService signalingService;
    private final ControlSessionRepository controlSessionRepository;
    private final SessionService sessionService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService graceScheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, ScheduledFuture<?>> pendingTerminations = new ConcurrentHashMap<>();

    public SignalingWebSocketHandler(SignalingService signalingService,
                                   ControlSessionRepository controlSessionRepository,
                                   SessionService sessionService){
        this.signalingService = signalingService;
        this.controlSessionRepository = controlSessionRepository;
        this.sessionService = sessionService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {

        // Allow larger signaling payloads (file transfer chunks)
        session.setTextMessageSizeLimit(512 * 1024);

        URI uri = session.getUri();
        if (uri == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        Map<String, String> params = UriComponentsBuilder.fromUri(uri)
                .build()
                .getQueryParams()
                .toSingleValueMap();

        String token = params.get("token");
        String role = params.get("role");

        if (token == null || role == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        ControlSession controlSession =
                controlSessionRepository.findBySignalingToken(token)
                        .orElse(null);

        if (controlSession == null ||
                controlSession.getStatus() != SessionStatus.ACTIVE) {
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        // 🔐 Vérifier le rôle
        if (!role.equals("viewer") && !role.equals("agent")) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        // 🔐 Optionnel : vérifier machineId côté agent
        if (role.equals("agent")) {
            // ici tu peux vérifier machineId envoyé par l’agent
        }

        signalingService.register(
                String.valueOf(controlSession.getId()),
                role,
                session
        );

        cancelPendingTermination(String.valueOf(controlSession.getId()));

        // Stocker le sessionId dans les attributs de la session WebSocket
        session.getAttributes().put("sessionId", String.valueOf(controlSession.getId()));
        session.getAttributes().put("role", role);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {

        SignalMessage msg = mapper.readValue(message.getPayload(), SignalMessage.class);

        // On attend OFFER/ANSWER/ICE
        if (msg.getType() == null || msg.getTo() == null) {
            session.sendMessage(new TextMessage(error("Invalid message: type/to required")));
            return;
        }

        // Récupérer sessionId depuis les attributs (stocké à la connexion)
        String sessionId = (String) session.getAttributes().get("sessionId");
        if (sessionId == null) {
            session.sendMessage(new TextMessage(error("Missing sessionId")));
            return;
        }

        WebSocketSession peer = signalingService.getPeer(sessionId, msg.getTo());
        if (peer == null || !peer.isOpen()) {
            session.sendMessage(new TextMessage(error("Peer not connected: " + msg.getTo())));
            return;
        }

        // Relay brut
        peer.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = (String) session.getAttributes().get("sessionId");
        String role = (String) session.getAttributes().get("role");
        if (sessionId != null) {
            notifyAndClosePeer(sessionId, role);
            signalingService.remove(sessionId, session);
            scheduleTerminationIfRoomEmpty(sessionId);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        String sessionId = (String) session.getAttributes().get("sessionId");
        String role = (String) session.getAttributes().get("role");
        if (sessionId != null) {
            notifyAndClosePeer(sessionId, role);
            signalingService.remove(sessionId, session);
            scheduleTerminationIfRoomEmpty(sessionId);
        }
    }

    private void notifyAndClosePeer(String sessionId, String role) {
        try {
            String peerRole = "agent".equals(role) ? "viewer" : "agent";
            WebSocketSession peer = signalingService.getPeer(sessionId, peerRole);
            if (peer != null && peer.isOpen()) {
                SignalMessage leave = new SignalMessage();
                leave.setType(SignalType.LEAVE);
                leave.setFrom(role == null ? "unknown" : role);
                leave.setTo(peerRole);
                leave.setPayload(Map.of("reason", "peer_disconnected"));
                peer.sendMessage(new TextMessage(mapper.writeValueAsString(leave)));
            }
        } catch (Exception ex) {
            log.debug("Failed to notify/close peer for session {}: {}", sessionId, ex.getMessage());
        }
    }

    private void scheduleTerminationIfRoomEmpty(String sessionId) {
        if (!signalingService.isRoomEmpty(sessionId)) {
            return;
        }
        cancelPendingTermination(sessionId);
        ScheduledFuture<?> future = graceScheduler.schedule(() -> terminateSessionIfStillEmpty(sessionId),
                SESSION_GRACE_PERIOD_SECONDS,
                TimeUnit.SECONDS);
        pendingTerminations.put(sessionId, future);
    }

    private void terminateSessionIfStillEmpty(String sessionId) {
        pendingTerminations.remove(sessionId);
        if (signalingService.isRoomEmpty(sessionId)) {
            terminateSession(sessionId);
        }
    }

    private void cancelPendingTermination(String sessionId) {
        ScheduledFuture<?> pending = pendingTerminations.remove(sessionId);
        if (pending != null) {
            pending.cancel(false);
        }
    }

    @PreDestroy
    public void shutdownGraceScheduler() {
        graceScheduler.shutdownNow();
    }

    private void terminateSession(String sessionId) {
        try {
            Long id = Long.parseLong(sessionId);
            ControlSession current = controlSessionRepository.findById(id).orElse(null);
            if (current != null && current.getStatus() == SessionStatus.ACTIVE) {
                sessionService.stopSession(id);
            }
        } catch (Exception ex) {
            log.warn("Unable to terminate session {} on signaling disconnect: {}", sessionId, ex.getMessage());
        }
    }

    private String getQueryParam(WebSocketSession session, String key) {
        URI uri = session.getUri();
        if (uri == null) return null;
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(key);
    }

    private String error(String message) throws Exception {
        SignalMessage err = new SignalMessage();
        err.setType(SignalType.ERROR);
        err.setFrom("server");
        err.setTo("client");
        err.setPayload(Map.of("message", message));
        return mapper.writeValueAsString(err);
    }
}
