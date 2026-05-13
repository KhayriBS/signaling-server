package com.lumiere.transport.remoteitsupportserver.ai.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Une ligne par appel IA. On garde la commande, le JSON brut renvoye par Gemini
 * (parseable cote front pour rejouer), le status ("ok"/"error") et le message
 * d'erreur eventuel — utile pour debug / facturation / metriques d'usage.
 *
 * Ne stocke PAS le screenshot lui-meme (trop volumineux : 200-800 KB par ligne).
 * Si tu veux l'audit visuel, externalise vers S3/MinIO et garde l'URL ici.
 */
@Entity
@Table(name = "ai_sessions", indexes = {
        @Index(name = "idx_ai_sessions_session_id", columnList = "sessionId"),
        @Index(name = "idx_ai_sessions_created_at", columnList = "createdAt")
})
public class AiSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Reference textuelle vers ControlSession.id — string pour souplesse. */
    @Column(nullable = false, length = 64)
    private String sessionId;

    @Column(length = 128)
    private String adminUser;

    @Column(nullable = false, length = 2000)
    private String command;

    /** JSON brut de la liste d'actions (ou {@code null} si erreur avant parse). */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String actionsJson;

    /** "ok" | "error" — petit volume, varchar suffit. */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 1000)
    private String errorMessage;

    /** Latence totale du round-trip Gemini (ms) — utile pour metriques. */
    private Long latencyMs;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public AiSession() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getAdminUser() { return adminUser; }
    public void setAdminUser(String adminUser) { this.adminUser = adminUser; }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public String getActionsJson() { return actionsJson; }
    public void setActionsJson(String actionsJson) { this.actionsJson = actionsJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
