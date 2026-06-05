package com.lumiere.transport.remoteitsupportserver.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Réponse Gemini publiée vers le frontend après analyse d'un frame.
 *
 * Le champ {@code done} est central pour la boucle agentic : si {@code true},
 * le frontend arrête la boucle et libère {@code aiBusy}. Si {@code false} (ou
 * absent), il déclenche un nouveau tour avec le screenshot post-exécution +
 * l'historique des actions de ce tour. Le frontend impose son propre cap
 * (5 itérations) pour éviter qu'un Gemini cassé ne boucle indéfiniment.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiActionEnvelope(
        String sessionId,
        String command,
        String status,
        String error,
        String rationale,
        List<AiAction> actions,
        Boolean done,
        Integer iteration
) {
    public static AiActionEnvelope ok(String sessionId, String command, String rationale,
                                      List<AiAction> actions, boolean done, Integer iteration) {
        return new AiActionEnvelope(sessionId, command, "ok", null, rationale, actions, done, iteration);
    }

    public static AiActionEnvelope error(String sessionId, String command, String error) {
        return new AiActionEnvelope(sessionId, command, "error", error, null, List.of(), null, null);
    }
}
