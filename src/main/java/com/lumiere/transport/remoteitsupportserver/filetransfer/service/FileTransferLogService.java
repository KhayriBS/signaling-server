package com.lumiere.transport.remoteitsupportserver.filetransfer.service;

import com.lumiere.transport.remoteitsupportserver.agent.entity.Agent;
import com.lumiere.transport.remoteitsupportserver.agent.repository.AgentRepository;
import com.lumiere.transport.remoteitsupportserver.filetransfer.entity.FileTransferLog;
import com.lumiere.transport.remoteitsupportserver.filetransfer.entity.FileTransferStatus;
import com.lumiere.transport.remoteitsupportserver.filetransfer.model.FileTransferHistoryEntry;
import com.lumiere.transport.remoteitsupportserver.filetransfer.model.FileTransferStartRequest;
import com.lumiere.transport.remoteitsupportserver.filetransfer.model.FileTransferUpdateRequest;
import com.lumiere.transport.remoteitsupportserver.filetransfer.repository.FileTransferLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Service
public class FileTransferLogService {

    private final FileTransferLogRepository repository;
    private final AgentRepository agentRepository;

    public FileTransferLogService(FileTransferLogRepository repository,
                                  AgentRepository agentRepository) {
        this.repository = repository;
        this.agentRepository = agentRepository;
    }

    /**
     * Enregistre le début d'un transfert. Idempotent : si {@code transferId}
     * existe déjà, on met à jour la ligne (utile si le client réémet START
     * après une déconnexion/reconnexion).
     */
    @Transactional
    public FileTransferLog start(FileTransferStartRequest req) {
        if (req == null || req.transferId() == null || req.transferId().isBlank()) {
            throw new IllegalArgumentException("transferId is required");
        }
        if (req.fromMachineId() == null || req.fromMachineId().isBlank()
                || req.toMachineId() == null || req.toMachineId().isBlank()) {
            throw new IllegalArgumentException("fromMachineId and toMachineId are required");
        }
        if (req.direction() == null) {
            throw new IllegalArgumentException("direction is required");
        }
        if (req.fileName() == null || req.fileName().isBlank()) {
            throw new IllegalArgumentException("fileName is required");
        }

        FileTransferLog log = repository.findByTransferId(req.transferId())
                .orElseGet(FileTransferLog::new);

        log.setTransferId(req.transferId());
        log.setSessionId(req.sessionId());
        log.setFromMachineId(req.fromMachineId().trim());
        log.setToMachineId(req.toMachineId().trim());
        log.setDirection(req.direction());
        log.setFileName(req.fileName());
        log.setFileSize(req.fileSize() == null ? 0L : req.fileSize());
        log.setMimeType(req.mimeType());
        log.setDestPath(req.destPath());
        log.setStatus(FileTransferStatus.IN_PROGRESS);
        log.setErrorMessage(null);
        if (log.getStartedAt() == null) {
            log.setStartedAt(Instant.now());
        }
        log.setCompletedAt(null);

        return repository.save(log);
    }

    /**
     * Met à jour le statut d'un transfert (COMPLETED / FAILED / CANCELLED).
     * Renvoie la ligne mise à jour ou jette {@link IllegalArgumentException}
     * si le transferId est inconnu.
     */
    @Transactional
    public FileTransferLog update(String transferId, FileTransferUpdateRequest req) {
        if (transferId == null || transferId.isBlank()) {
            throw new IllegalArgumentException("transferId is required");
        }
        if (req == null || req.status() == null) {
            throw new IllegalArgumentException("status is required");
        }

        FileTransferLog log = repository.findByTransferId(transferId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown transferId: " + transferId));

        log.setStatus(req.status());
        if (req.fileSize() != null && req.fileSize() > 0) {
            log.setFileSize(req.fileSize());
        }
        if (req.errorMessage() != null) {
            log.setErrorMessage(req.errorMessage());
        }
        if (req.destPath() != null && !req.destPath().isBlank()) {
            log.setDestPath(req.destPath());
        }

        if (req.status() != FileTransferStatus.IN_PROGRESS) {
            log.setCompletedAt(Instant.now());
        }

        return repository.save(log);
    }

    /**
     * Historique des transferts liés à une machine donnée.
     *
     * @param key       machineId direct OU connection_code à 6 chiffres
     * @param direction "incoming"/"outgoing"/"all"/null
     * @param status    "in_progress"/"completed"/"failed"/"cancelled"/"ended"/"all"/null
     * @param search    sous-chaîne libre cherchée dans fileName / fromMachineId / toMachineId
     */
    @Transactional(readOnly = true)
    public List<FileTransferHistoryEntry> getHistory(
            String key,
            String direction,
            String status,
            String search
    ) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("machineId or connectionCode is required");
        }

        String resolvedMachineId = key.trim();
        if (resolvedMachineId.matches("\\d{6}")) {
            resolvedMachineId = agentRepository.findByConnectionCode(resolvedMachineId)
                    .map(Agent::getMachineId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No agent found for connection code: " + key));
        }

        String normalizedDirection = normalizeDirection(direction);
        List<FileTransferStatus> statuses = resolveStatusFilter(status);
        String normalizedSearch = (search == null) ? null : search.trim();
        if (normalizedSearch != null && normalizedSearch.isEmpty()) {
            normalizedSearch = null;
        }

        final String finalMachineId = resolvedMachineId;
        return repository
                .findHistoryForMachine(finalMachineId, normalizedDirection, statuses, normalizedSearch)
                .stream()
                .map(log -> FileTransferHistoryEntry.fromEntity(log, finalMachineId))
                .toList();
    }

    private String normalizeDirection(String direction) {
        if (direction == null) return null;
        String trimmed = direction.trim().toLowerCase();
        return switch (trimmed) {
            case "incoming", "entrant", "entrante", "entrantes", "received", "recus", "reçu" -> "incoming";
            case "outgoing", "sortant", "sortante", "sortantes", "sent", "envoyes", "envoyé" -> "outgoing";
            case "", "all", "tous", "tout" -> null;
            default -> null;
        };
    }

    private List<FileTransferStatus> resolveStatusFilter(String status) {
        if (status == null) return null;
        String trimmed = status.trim().toLowerCase();
        return switch (trimmed) {
            case "", "all", "tous" -> null;
            case "in_progress", "in-progress", "active", "running", "en_cours", "en-cours" ->
                    List.of(FileTransferStatus.IN_PROGRESS);
            case "completed", "done", "terminees", "terminée", "terminées", "ok" ->
                    List.of(FileTransferStatus.COMPLETED);
            case "failed", "error", "erreur", "echec", "échec" ->
                    List.of(FileTransferStatus.FAILED);
            case "cancelled", "canceled", "annule", "annulé", "annulees", "annulées" ->
                    List.of(FileTransferStatus.CANCELLED);
            case "ended", "finished" ->
                    Arrays.asList(
                            FileTransferStatus.COMPLETED,
                            FileTransferStatus.FAILED,
                            FileTransferStatus.CANCELLED
                    );
            default -> {
                try {
                    yield List.of(FileTransferStatus.valueOf(status.trim().toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                    yield null;
                }
            }
        };
    }
}
