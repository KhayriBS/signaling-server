package com.lumiere.transport.remoteitsupportserver.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Action atomique generee par Gemini et executee par l'agent Rust distant.
 *
 * Schema "shape" volontairement plat (tous les champs sont nullable selon le
 * {@code type}) — c'est plus simple a serialiser cote Java/Rust qu'une vraie
 * union polymorphe via {@code @JsonTypeInfo}, et ca matche directement le JSON
 * que Gemini est instructe de produire.
 *
 * Types supportes : click, double_click, move, type_text, key, shell,
 * screenshot, wait.
 *
 * Coordonnees x/y NORMALISEES [0, 1] relativement a la frame capturee.
 */
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
        Integer ms
) {
}
