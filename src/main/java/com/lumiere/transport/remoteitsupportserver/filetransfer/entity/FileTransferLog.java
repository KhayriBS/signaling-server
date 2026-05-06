package com.lumiere.transport.remoteitsupportserver.filetransfer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Trace d'un transfert de fichier P2P (WebRTC DataChannel) entre deux machines.
 *
 * On enregistre :
 *  - l'identifiant côté client ({@code transferId} — UUID généré par le viewer/agent)
 *  - les machines source/cible ({@code fromMachineId} / {@code toMachineId})
 *  - le sens ({@link FileTransferDirection})
 *  - les métadonnées du fichier (nom, taille, mime)
 *  - les horodatages de début/fin et le statut final
 *
 * Les octets eux-mêmes ne transitent jamais par le serveur — c'est un journal
 * d'audit, pas un stockage. La paire {@code transferId} est unique pour
 * supporter l'idempotence (si le client réémet START, on UPDATE plutôt que
 * de créer un doublon).
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(
        name = "file_transfer_logs",
        indexes = {
                @Index(name = "idx_ftl_transfer_id", columnList = "transferId", unique = true),
                @Index(name = "idx_ftl_from_machine", columnList = "fromMachineId"),
                @Index(name = "idx_ftl_to_machine", columnList = "toMachineId"),
                @Index(name = "idx_ftl_session", columnList = "sessionId"),
                @Index(name = "idx_ftl_started_at", columnList = "startedAt")
        }
)
public class FileTransferLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UUID généré côté client — sert d'idempotency key. */
    @Column(nullable = false, unique = true, length = 64)
    private String transferId;

    /** Session WebRTC associée (peut être null si transfert hors session). */
    private Long sessionId;

    @Column(nullable = false, length = 128)
    private String fromMachineId;

    @Column(nullable = false, length = 128)
    private String toMachineId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FileTransferDirection direction;

    @Column(nullable = false, length = 512)
    private String fileName;

    /** Taille en octets (peut rester 0 tant que la fin n'est pas atteinte). */
    @Column(nullable = false)
    private long fileSize;

    @Column(length = 128)
    private String mimeType;

    /** Chemin de destination réel sur la machine cible (informatif). */
    @Column(length = 1024)
    private String destPath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FileTransferStatus status;

    /** Message d'erreur en cas de FAILED. */
    @Column(length = 1024)
    private String errorMessage;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant completedAt;
}
