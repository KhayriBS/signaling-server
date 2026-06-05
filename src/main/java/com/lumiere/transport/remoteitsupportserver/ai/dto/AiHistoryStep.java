package com.lumiere.transport.remoteitsupportserver.ai.dto;

/**
 * Une étape passée dans la boucle agentic, envoyée à Gemini au tour N+1 pour
 * lui donner le contexte de ce qu'il a déjà fait/observé. Volontairement
 * minimal (texte uniquement, pas d'image) pour limiter la taille du payload —
 * le screenshot du tour courant porte déjà l'état visuel.
 *
 * @param iteration   numéro du tour (0-indexed)
 * @param rationale   rationale renvoyée par Gemini à ce tour
 * @param actionsJson liste des actions exécutées, en JSON serialisé compact
 * @param resultText  résumé des résultats (stdout shell, "click OK", erreurs…).
 *                    Concaténation tronquée à ~600 chars pour rester compact.
 */
public record AiHistoryStep(
        Integer iteration,
        String rationale,
        String actionsJson,
        String resultText
) {
}
