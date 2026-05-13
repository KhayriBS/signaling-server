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
 * Comme l'endpoint /ws/chat n'authentifie pas le STOMP CONNECT (cf
 * StompWebSocketConfig), il n'y a pas de {@code Principal}. On utilise le
 * {@code simpSessionId} comme cle "user" — Spring le reconnait quand on lui
 * passe explicitement comme header via {@code SimpMessageHeaderAccessor}.
 *
 * L'appel Gemini est exporte sur un pool de threads dedie pour ne pas bloquer
 * le thread STOMP inbound (latence HTTP 1-10s). Le client recoit la reponse
 * de facon asynchrone — c'est invisible cote front car le client est deja
 * abonne au queue.
 */
@Controller
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    /** Destination relative au user-prefix ("/user" + ce path). */
    private static final String USER_ACTIONS_DESTINATION = "/queue/ai/actions";

    /**
     * Destination separee pour les erreurs cote serveur (timeout, exception
     * inattendue, etc.) que le front peut surveiller independamment de
     * /queue/ai/actions pour declencher une UI specifique (banner / toast).
     */
    private static final String USER_ERROR_DESTINATION = "/queue/ai/error";

    /**
     * Limite stricte sur l'appel IA complet (build payload + Gemini + parse
     * + persistance + retry 429).
     *
     * Budget detaille :
     *   • HTTP attempt 1     : jusqu'a 25 s (HTTP_READ_TIMEOUT cote service)
     *   • Backoff 429        : jusqu'a 8 s  (parseGeminiRetryDelayMs, cape a 8 s)
     *   • HTTP attempt 2     : reste = 45 - 25 - 8 = 12 s
     * Si l'attente totale depasse 45s, le user prefere voir "timeout" plutot que
     * d'attendre indefiniment. Le HTTP_READ_TIMEOUT cote service libere quand
     * meme le worker thread proprement, evitant les fuites de pool.
     */
    private static final int AI_PIPELINE_TIMEOUT_SECONDS = 45;

    private final AiAgentService aiAgentService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Pool dedie — taille bornee pour eviter qu'un burst de commandes IA ne
     * sature la JVM (chaque appel Gemini consomme ~1-2 MB de heap pour le base64).
     */
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

        // ─── Pipeline asynchrone avec timeout strict ──────────────────────
        // CompletableFuture.supplyAsync + orTimeout : si l'analyse depasse
        // AI_PIPELINE_TIMEOUT_SECONDS, le future est complete exceptionnellement
        // avec TimeoutException, et on envoie l'erreur sans bloquer le pool.
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

    /**
     * Alternative REST a {@link #onFrame} pour les payloads volumineux. Le frame
     * IA (screenshot ~95 KB base64 + JSON wrapper) peut etre jete par certains
     * proxys WebSocket (Render free-tier notamment) — passer en HTTP POST evite
     * la limite de taille des frames STOMP / SockJS.
     *
     * La reponse Gemini arrive toujours via STOMP sur {@code /topic/ai/<sessionId>}
     * (et /user/queue/ai/actions en duplicate) — le client doit etre abonne AVANT
     * d'envoyer le POST.
     *
     * Repond immediatement avec {@code accepted:true, requestId:...} et lance
     * l'analyse Gemini en arriere-plan. Le client n'a qu'a attendre l'envelope
     * STOMP retour.
     */
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
        // La reponse part SEULEMENT sur /topic/ai/<sessionId> car on n'a
        // pas de simpSessionId STOMP ici (REST decouple du WebSocket).
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

    /**
     * Envoie le resultat IA au client via DEUX canaux paralleles :
     *
     *   1. /user/queue/ai/actions   (resolution user-destination Spring)
     *   2. /topic/ai/<sessionId>    (broadcast topic — voie robuste, pas de
     *                                resolution magique a faire)
     *
     * Le double envoi sert de garde-fou : si la voie 1 echoue silencieusement
     * (probleme de Principal/Authentication, race sur le simpSessionId, etc.),
     * la voie 2 delivre quand meme. Le client doit dedupliquer cote front
     * (mais c'est une boucle infiniment plus simple a debugger qu'un message
     * jamais arrive).
     */
    private void sendToUser(String stompSessionId, AiActionEnvelope envelope) {
        publishBothChannels(stompSessionId, envelope, USER_ACTIONS_DESTINATION);
    }

    /**
     * Envoie une erreur via deux canaux paralleles :
     *
     *   1. /user/queue/ai/error     (canal dedie aux erreurs)
     *   2. /user/queue/ai/actions   (canal principal — pour les clients qui
     *                                n'ecoutent que celui-ci)
     *   3. /topic/ai/<sessionId>    (topic robuste, garde-fou)
     */
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
