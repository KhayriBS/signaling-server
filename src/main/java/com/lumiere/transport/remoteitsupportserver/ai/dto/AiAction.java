package com.lumiere.transport.remoteitsupportserver.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiAction(
        String type,
        Double x,
        Double y,
        String button,
        String text,
        String key,
        List<String> modifiers,
        String cmd,
        String shell,
        Integer ms,
        // ── scroll ───────────────────────────────────────────────────────
        /** Scroll vertical : positif = descendre, negatif = remonter (clics de molette). */
        Integer dy,
        /** Scroll horizontal (optionnel, rare). */
        Integer dx,
        // ── drag ─────────────────────────────────────────────────────────
        /** Coordonnees de destination du drag (x/y = point de depart). */
        Double destX,
        Double destY
) {
}
