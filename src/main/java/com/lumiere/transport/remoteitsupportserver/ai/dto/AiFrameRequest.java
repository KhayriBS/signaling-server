package com.lumiere.transport.remoteitsupportserver.ai.dto;

/**
 * Payload publié par le frontend Tauri sur {@code /app/ai/frame}.
 *
 * @param sessionId           id de la ControlSession active (string pour permettre
 *                            les clients qui n'ont pas encore le numerique)
 * @param command             instruction en langage naturel ("installe driver X")
 * @param screenshot          JPEG base64 SANS le prefixe {@code data:image/jpeg;base64,}
 * @param frameWidth          largeur native du flux video capture (px) — sert au
 *                            client agent pour denormaliser les coordonnees
 * @param frameHeight         hauteur native du flux video capture (px)
 * @param technicianUsername  nom du technicien (pour audit) — facultatif
 */
public record AiFrameRequest(
        String sessionId,
        String command,
        String screenshot,
        Integer frameWidth,
        Integer frameHeight,
        String technicianUsername
) {
}
