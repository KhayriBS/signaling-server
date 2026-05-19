package com.lumiere.transport.remoteitsupportserver.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

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
