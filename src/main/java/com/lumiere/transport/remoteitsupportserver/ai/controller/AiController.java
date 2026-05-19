package com.lumiere.transport.remoteitsupportserver.ai.controller;

import com.lumiere.transport.remoteitsupportserver.ai.dto.AiActionEnvelope;
import com.lumiere.transport.remoteitsupportserver.ai.dto.AiFrameRequest;
import com.lumiere.transport.remoteitsupportserver.ai.service.AiAgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Controller STOMP — endpoint unique {@code /app/ai/frame}.
 *
 * Reponse envoyee sur {@code /user/queue/ai/actions} via
 * {@link SimpMessagingTemplate#convertAndSendToUser(String, String, Object, java.util.Map)}.

 */
@Controller
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    /** Destination relative au user-prefix ("/user" + ce path). */
    private static final String USER_ACTIONS_DESTINATION = "/queue/ai/actions";

    private static final String USER_ERROR_DESTINATION = "/queue/ai/error";
    private static final int AI_PIPELINE_TIMEOUT_SECONDS = 45;
    private final AiAgentService aiAgentService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ExecutorService aiExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "ai-agent-worker");
        t.setDaemon(true);
        return t;
    });

    public AiController(AiAgentService aiAgentService,
                        SimpMessagingTemplate messagingTemplate) {
        this.aiAgentService = aiAgentService;
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/ai/frame")
    public void onFrame(@Payload AiFrameRequest payload,
                        SimpMessageHeaderAccessor accessor) {
        String stompSessionId = accessor.getSessionId();
        if (stompSessionId == null) {
            log.warn("[ai] /ai/frame received without STOMP session id, dropping");
            return;
        }

        // ─── Reception ────────────────────────────────────────────────────
        long tReceived = System.currentTimeMillis();
        if (payload == null) {
            log.warn("[ai] [{}] /ai/frame received with null payload", stompSessionId);
            sendError(stompSessionId, null, null, "Empty payload");
            return;
        }
        int screenshotBytes = payload.screenshot() == null ? 0 : payload.screenshot().length();
        log.info("[ai] [{}] ◀ FRAME received | sessionId={} | cmd=\"{}\" | screenshot={} chars ({}x{})",
                stompSessionId,
                payload.sessionId(),
                truncate(payload.command(), 100),
                screenshotBytes,
                payload.frameWidth(),
                payload.frameHeight()
        );
        // ─── Pipeline async ──────────────────────────────────────────────

        CompletableFuture
                .supplyAsync(() -> {
                    log.info("[ai] [{}] → calling Gemini (model=gemini-2.5-flash)", stompSessionId);
                    return aiAgentService.analyse(payload);
                }, aiExecutor)
                .orTimeout(AI_PIPELINE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((envelope, throwable) -> {
                    long elapsed = System.currentTimeMillis() - tReceived;

                    if (throwable instanceof TimeoutException) {
                        log.warn("[ai] [{}] ✖ TIMEOUT after {}ms (limit={}s)",
                                stompSessionId, elapsed, AI_PIPELINE_TIMEOUT_SECONDS);
                        sendError(stompSessionId, payload.sessionId(), payload.command(),
                                "Gemini timeout (>" + AI_PIPELINE_TIMEOUT_SECONDS + "s)");
                        return;
                    }
                    if (throwable != null) {
                        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                        log.error("[ai] [{}] ✖ pipeline error after {}ms: {}",
                                stompSessionId, elapsed, cause.toString(), cause);
                        sendError(stompSessionId, payload.sessionId(), payload.command(),
                                "Internal error: " + cause.getClass().getSimpleName());
                        return;
                    }

                    // ─── Reponse Gemini recue ────────────────────────────
                    int actionCount = envelope.actions() == null ? 0 : envelope.actions().size();
                    log.info("[ai] [{}] ◀ Gemini response | status={} | actions={} | rationale=\"{}\" | total {}ms",
                            stompSessionId,
                            envelope.status(),
                            actionCount,
                            truncate(envelope.rationale(), 100),
                            elapsed
                    );

                    log.info("[ai] [{}] ▶ sending actions to /user/queue/ai/actions", stompSessionId);
                    sendToUser(stompSessionId, envelope);
                });
    }

  
    @PostMapping("/ai/frame")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> postFrame(@RequestBody AiFrameRequest payload) {
        String requestId = UUID.randomUUID().toString();

        if (payload == null) {
            log.warn("[ai] [REST/{}] payload null", requestId);
            return ResponseEntity.badRequest().body(Map.of(
                    "accepted", false,
                    "requestId", requestId,
                    "error", "Empty payload"
            ));
        }
        if (payload.sessionId() == null || payload.sessionId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "accepted", false,
                    "requestId", requestId,
                    "error", "sessionId required (used as STOMP topic key)"
            ));
        }

        long tReceived = System.currentTimeMillis();
        int screenshotBytes = payload.screenshot() == null ? 0 : payload.screenshot().length();
        log.info("[ai] [REST/{}] ◀ FRAME received | sessionId={} | cmd=\"{}\" | screenshot={} chars ({}x{})",
                requestId,
                payload.sessionId(),
                truncate(payload.command(), 100),
                screenshotBytes,
                payload.frameWidth(),
                payload.frameHeight()
        );

        // ─── Pipeline async, identique a onFrame() ──────────────────────
        CompletableFuture
                .supplyAsync(() -> {
                    log.info("[ai] [REST/{}] → calling Gemini (model=gemini-2.5-flash)", requestId);
                    return aiAgentService.analyse(payload);
                }, aiExecutor)
                .orTimeout(AI_PIPELINE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((envelope, throwable) -> {
                    long elapsed = System.currentTimeMillis() - tReceived;
                    if (throwable instanceof TimeoutException) {
                        log.warn("[ai] [REST/{}] ✖ TIMEOUT after {}ms (limit={}s)",
                                requestId, elapsed, AI_PIPELINE_TIMEOUT_SECONDS);
                        publishToTopic("REST/" + requestId,
                                AiActionEnvelope.error(payload.sessionId(), payload.command(),
                                        "Gemini timeout (>" + AI_PIPELINE_TIMEOUT_SECONDS + "s)"));
                        return;
                    }
                    if (throwable != null) {
                        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                        log.error("[ai] [REST/{}] ✖ pipeline error after {}ms: {}",
                                requestId, elapsed, cause.toString(), cause);
                        publishToTopic("REST/" + requestId,
                                AiActionEnvelope.error(payload.sessionId(), payload.command(),
                                        "Internal error: " + cause.getClass().getSimpleName()));
                        return;
                    }
                    int actionCount = envelope.actions() == null ? 0 : envelope.actions().size();
                    log.info("[ai] [REST/{}] ◀ Gemini response | status={} | actions={} | rationale=\"{}\" | total {}ms",
                            requestId,
                            envelope.status(),
                            actionCount,
                            truncate(envelope.rationale(), 100),
                            elapsed
                    );
                    log.info("[ai] [REST/{}] ▶ publishing to /topic/ai/{}",
                            requestId, payload.sessionId());
                    publishToTopic("REST/" + requestId, envelope);
                });

        return ResponseEntity.ok(Map.of(
                "accepted", true,
                "requestId", requestId,
                "sessionId", payload.sessionId(),
                "responseChannel", "/topic/ai/" + payload.sessionId()
        ));
    }

  
    private void sendToUser(String stompSessionId, AiActionEnvelope envelope) {
        publishBothChannels(stompSessionId, envelope, USER_ACTIONS_DESTINATION);
    }

    private void sendError(String stompSessionId, String sessionId, String command, String message) {
        var errorEnvelope = AiActionEnvelope.error(sessionId, command, message);

        // Voie /user/queue/ai/error
        publishToUserDestination(stompSessionId, errorEnvelope, USER_ERROR_DESTINATION);
        // + Voies /user/queue/ai/actions ET /topic/ai/<sessionId>
        publishBothChannels(stompSessionId, errorEnvelope, USER_ACTIONS_DESTINATION);
    }

    private void publishBothChannels(String stompSessionId, AiActionEnvelope envelope,
                                     String userDestination) {
        publishToUserDestination(stompSessionId, envelope, userDestination);
        publishToTopic(stompSessionId, envelope);
    }

    private void publishToUserDestination(String stompSessionId, AiActionEnvelope envelope,
                                          String userDestination) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(stompSessionId);
        headers.setLeaveMutable(true);
        try {
            messagingTemplate.convertAndSendToUser(
                    stompSessionId,
                    userDestination,
                    envelope,
                    headers.getMessageHeaders()
            );
            log.info("[ai] [{}] ▶ published to /user{}", stompSessionId, userDestination);
        } catch (Exception ex) {
            log.warn("[ai] [{}] failed user-destination {}: {}",
                    stompSessionId, userDestination, ex.getMessage());
        }
    }

    private void publishToTopic(String stompSessionId, AiActionEnvelope envelope) {
        String sid = envelope.sessionId();
        if (sid == null || sid.isBlank()) {
            log.warn("[ai] [{}] cannot publish to topic — envelope has no sessionId",
                    stompSessionId);
            return;
        }
        String topic = "/topic/ai/" + sid;
        try {
            messagingTemplate.convertAndSend(topic, envelope);
            log.info("[ai] [{}] ▶ published to {}", stompSessionId, topic);
        } catch (Exception ex) {
            log.warn("[ai] [{}] failed topic {}: {}",
                    stompSessionId, topic, ex.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
