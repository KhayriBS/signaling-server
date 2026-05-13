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

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

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

    private final AiAgentService aiAgentService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Pool dedie — taille bornee pour eviter qu'un burst de commandes IA ne
     * sature la JVM (chaque appel Gemini consomme ~1-2 MB de heap pour le base64).
     */
    private final Executor aiExecutor = Executors.newFixedThreadPool(4, r -> {
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
            log.warn("[ai] received /ai/frame without a STOMP session id, dropping");
            return;
        }

        String preview = payload == null
                ? "null"
                : "sessionId=" + payload.sessionId() + ", cmd='" + truncate(payload.command(), 80) + "'";
        log.info("[ai] frame received from stomp-session={} ({})", stompSessionId, preview);

        // Off-thread : retire le handler STOMP du chemin critique (1-10s Gemini).
        aiExecutor.execute(() -> {
            AiActionEnvelope envelope;
            try {
                envelope = aiAgentService.analyse(payload);
            } catch (Exception ex) {
                log.error("[ai] unexpected failure analysing frame", ex);
                envelope = AiActionEnvelope.error(
                        payload == null ? null : payload.sessionId(),
                        payload == null ? null : payload.command(),
                        "Internal error: " + ex.getClass().getSimpleName());
            }
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
            log.warn("[ai] failed to send actions to user-destination (session={}): {}",
                    stompSessionId, ex.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
