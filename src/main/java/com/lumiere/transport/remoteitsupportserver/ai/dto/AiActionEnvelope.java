package com.lumiere.transport.remoteitsupportserver.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Reponse envoyee par le backend sur {@code /user/queue/ai/actions} apres avoir
 * fait analyser le screenshot par Gemini.
 *
 * Le {@code status} est "ok" si Gemini a renvoye un JSON parseable, "error"
 * sinon (timeout, quota, parse fail, …) — dans ce cas {@code error} contient
 * un message lisible pour l'utilisateur et {@code actions} est une liste vide.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiActionEnvelope(
        String sessionId,
        String command,
        String status,
        String error,
        String rationale,
        List<AiAction> actions
) {
    public static AiActionEnvelope ok(String sessionId, String command, String rationale, List<AiAction> actions) {
        return new AiActionEnvelope(sessionId, command, "ok", null, rationale, actions);
    }

    public static AiActionEnvelope error(String sessionId, String command, String error) {
        return new AiActionEnvelope(sessionId, command, "error", error, null, List.of());
    }
}
