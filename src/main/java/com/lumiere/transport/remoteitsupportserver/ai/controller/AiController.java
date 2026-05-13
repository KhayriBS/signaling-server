package com.lumiere.transport.remoteitsupportserver.ai.controller;

import com.lumiere.transport.remoteitsupportserver.ai.dto.AiActionEnvelope;
import com.lumiere.transport.remoteitsupportserver.ai.dto.AiFrameRequest;
import com.lumiere.transport.remoteitsupportserver.ai.service.AiAgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

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
     * Envoie a la session STOMP donnee en faisant croire a Spring que cette
     * session est aussi un "user" (le UserDestinationResolver accepte un
     * sessionId nu comme cle d'user quand aucun Principal n'est attache).
     */
    private void sendToUser(String stompSessionId, AiActionEnvelope envelope) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(stompSessionId);
        headers.setLeaveMutable(true);

        try {
            messagingTemplate.convertAndSendToUser(
                    stompSessionId,
                    USER_ACTIONS_DESTINATION,
                    envelope,
                    headers.getMessageHeaders()
            );
        } catch (Exception ex) {
            log.warn("[ai] [{}] failed to send actions to user-destination: {}",
                    stompSessionId, ex.getMessage());
        }
    }

    /**
     * Envoie une erreur cote serveur (timeout, exception inattendue, payload
     * invalide). Pousse en parallele sur /queue/ai/error (canal dedie) ET
     * sur /queue/ai/actions sous forme d'envelope error, pour que le front
     * recoive l'erreur quel que soit le subscribe choisi.
     */
    private void sendError(String stompSessionId, String sessionId, String command, String message) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(stompSessionId);
        headers.setLeaveMutable(true);

        var errorEnvelope = AiActionEnvelope.error(sessionId, command, message);

        // 1) Canal dedie pour les erreurs (le front peut afficher banner/toast).
        try {
            messagingTemplate.convertAndSendToUser(
                    stompSessionId,
                    USER_ERROR_DESTINATION,
                    errorEnvelope,
                    headers.getMessageHeaders()
            );
        } catch (Exception ex) {
            log.warn("[ai] [{}] failed to send to {}: {}",
                    stompSessionId, USER_ERROR_DESTINATION, ex.getMessage());
        }

        // 2) Canal actions habituel — pour les fronts qui n'ecoutent QUE celui-ci.
        try {
            messagingTemplate.convertAndSendToUser(
                    stompSessionId,
                    USER_ACTIONS_DESTINATION,
                    errorEnvelope,
                    headers.getMessageHeaders()
            );
        } catch (Exception ex) {
            log.warn("[ai] [{}] failed to send error fallback to {}: {}",
                    stompSessionId, USER_ACTIONS_DESTINATION, ex.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
