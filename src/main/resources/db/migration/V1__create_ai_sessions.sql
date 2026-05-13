-- ─────────────────────────────────────────────────────────────────────────────
-- ai_sessions
--
-- Une ligne par round-trip "commande IA + frame → Gemini → plan d'actions".
-- Sert pour : audit / debug / quotas / facturation / metrics d'usage.
--
-- Cree automatiquement par Hibernate (spring.jpa.hibernate.ddl-auto=update)
-- au premier demarrage. Ce script SQL est l'autorite de reference pour les
-- environnements ou ddl-auto serait desactive (prod hardenee, replicas, etc.).
--
-- IMPORTANT : on NE stocke PAS le screenshot lui-meme — 200-800 KB par ligne,
-- ca exploserait la table en quelques jours. Si tu veux l'audit visuel,
-- externalise vers S3/MinIO/blob et garde l'URL.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS ai_sessions (
    id              BIGINT          NOT NULL AUTO_INCREMENT,

    -- Reference textuelle vers control_sessions.id. String (pas FK) pour
    -- accommoder les sessions guests / archives historiques ou la session
    -- d'origine a deja ete purgee.
    session_id      VARCHAR(64)     NOT NULL,

    -- Technicien qui a envoye la commande. Nullable car les sessions guests
    -- n'ont pas toujours d'identite stable.
    admin_user      VARCHAR(128)    NULL,

    -- Prompt utilisateur ("installe driver imprimante HP", "ouvre le panneau
    -- de configuration", …). Tronque a 2000 chars cote service.
    command         VARCHAR(2000)   NOT NULL,

    -- JSON brut du tableau d'actions ([{type:"click",...}, ...]).
    -- TEXT = jusqu'a 64 KB, largement suffisant pour 32 actions max.
    -- NULL si erreur AVANT le parse (timeout, quota, JSON malforme).
    actions_json    TEXT            NULL,

    -- "ok" | "error" — pas un ENUM pour rester souple (futurs etats : "refused",
    -- "partial", "cancelled" sans migration).
    status          VARCHAR(16)     NOT NULL,

    -- Message d'erreur lisible si status='error'. Inclut l'exit code shell,
    -- le code HTTP Gemini, ou le motif de parse fail. Tronque a 1000 chars.
    error_message   VARCHAR(1000)   NULL,

    -- Latence totale du round-trip Gemini (ms). Inclut l'envoi du JPEG +
    -- inference + retour. Permet de detecter les regressions de perf.
    latency_ms      BIGINT          NULL,

    created_at      DATETIME(6)     NOT NULL,

    PRIMARY KEY (id),
    -- Index sur session_id pour "donne-moi l'historique IA de la session 42".
    KEY idx_ai_sessions_session_id (session_id),
    -- Index sur created_at pour les queries de monitoring/quota "appels
    -- IA des dernieres 24h".
    KEY idx_ai_sessions_created_at (created_at)
)
ENGINE=InnoDB
DEFAULT CHARSET=utf8mb4
COLLATE=utf8mb4_unicode_ci;
