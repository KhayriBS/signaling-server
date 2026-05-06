package com.lumiere.transport.remoteitsupportserver.filetransfer.model;

import com.lumiere.transport.remoteitsupportserver.filetransfer.entity.FileTransferDirection;
import com.lumiere.transport.remoteitsupportserver.filetransfer.entity.FileTransferLog;
import com.lumiere.transport.remoteitsupportserver.filetransfer.entity.FileTransferStatus;

import java.time.Duration;
import java.time.Instant;

/**
 * Vue de lecture d'un transfert pour l'écran "Historique des fichiers".
 *
 * {@code peerLabel} = identifiant de l'AUTRE PC (à afficher dans la carte) :
 *   - direction "incoming" (la machine reçoit) → fromMachineId
 *   - direction "outgoing" (la machine envoie) → toMachineId
 *
 * {@code listDirection} ("incoming"/"outgoing") est calculé en fonction du
 * point de vue de la machine appelante, indépendamment du sens UPLOAD/DOWNLOAD
 * stocké en base (qui est le sens technicien→agent ou inverse, fixe).
 */
public record FileTransferHistoryEntry(
        Long id,
        String transferId,
        Long sessionId,
        String fromMachineId,
        String toMachineId,
        FileTransferDirection direction,
        String listDirection,
        String peerLabel,
        String fileName,
        long fileSize,
        String mimeType,
        String destPath,
        FileTransferStatus status,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        Long durationMs
) {
    public static FileTransferHistoryEntry fromEntity(FileTransferLog log, String machineId) {
        String listDirection;
        if (machineId != null && machineId.equals(log.getToMachineId())) {
            listDirection = "incoming";
        } else if (machineId != null && machineId.equals(log.getFromMachineId())) {
            listDirection = "outgoing";
        } else {
            listDirection = "incoming";
        }

        String peerLabel = "incoming".equals(listDirection)
                ? log.getFromMachineId()
                : log.getToMachineId();

        Long durationMs = null;
        Instant start = log.getStartedAt();
        Instant end = log.getCompletedAt();
        if (start != null && end != null) {
            durationMs = Duration.between(start, end).toMillis();
        }

        return new FileTransferHistoryEntry(
                log.getId(),
                log.getTransferId(),
                log.getSessionId(),
                log.getFromMachineId(),
                log.getToMachineId(),
                log.getDirection(),
                listDirection,
                peerLabel,
                log.getFileName(),
                log.getFileSize(),
                log.getMimeType(),
                log.getDestPath(),
                log.getStatus(),
                log.getErrorMessage(),
                start,
                end,
                durationMs
        );
    }
}
