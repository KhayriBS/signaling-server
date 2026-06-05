package com.lumiere.transport.remoteitsupportserver.ai.dto;

import java.util.List;

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
 * @param iteration           tour courant dans la boucle agentic (0 = premier
 *                            appel, incrémenté à chaque relance automatique).
 *                            Sert à Gemini pour calibrer son comportement
 *                            (refuser de boucler indéfiniment, savoir qu'il a
 *                            déjà tenté quelque chose). {@code null} = mono-shot
 *                            classique (rétrocompat avec anciens clients).
 * @param history             résumé des tours précédents : rationale, actions
 *                            exécutées, résultat textuel. Permet à Gemini de
 *                            comprendre où il en est et de corriger sans répéter
 *                            la même action ratée. Vide ou null au premier tour.
 */
public record AiFrameRequest(
        String sessionId,
        String command,
        String screenshot,
        Integer frameWidth,
        Integer frameHeight,
        String technicianUsername,
        Integer iteration,
        List<AiHistoryStep> history
) {
}
