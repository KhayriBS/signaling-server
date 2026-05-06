package com.lumiere.transport.remoteitsupportserver.filetransfer.controller;

import com.lumiere.transport.remoteitsupportserver.common.dto.ApiResponse;
import com.lumiere.transport.remoteitsupportserver.filetransfer.entity.FileTransferLog;
import com.lumiere.transport.remoteitsupportserver.filetransfer.model.FileTransferHistoryEntry;
import com.lumiere.transport.remoteitsupportserver.filetransfer.model.FileTransferStartRequest;
import com.lumiere.transport.remoteitsupportserver.filetransfer.model.FileTransferUpdateRequest;
import com.lumiere.transport.remoteitsupportserver.filetransfer.service.FileTransferLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/file-transfers")
public class FileTransferLogController {

    private final FileTransferLogService service;

    public FileTransferLogController(FileTransferLogService service) {
        this.service = service;
    }

    /**
     * Enregistre le début d'un transfert P2P. Idempotent sur {@code transferId} :
     * un second POST avec le même transferId met à jour la ligne existante.
     */
    @PostMapping
    public ApiResponse<FileTransferLog> start(@RequestBody FileTransferStartRequest request) {
        return ApiResponse.success(service.start(request));
    }

    /**
     * Met à jour le statut d'un transfert (à appeler à la fin : COMPLETED,
     * FAILED, CANCELLED). Le {@code completedAt} est posé automatiquement
     * dès que le statut quitte IN_PROGRESS.
     */
    @PatchMapping("/{transferId}")
    public ApiResponse<FileTransferLog> update(
            @PathVariable String transferId,
            @RequestBody FileTransferUpdateRequest request) {

        return ApiResponse.success(service.update(transferId, request));
    }

    /**
     * Historique des transferts impliquant une machine donnée.
     *
     * Le {@code key} peut être soit le {@code machineId}, soit un
     * {@code connectionCode} à 6 chiffres (résolu en interne via la table
     * agents).
     *
     * Filtres :
     *  - direction : "incoming" / "outgoing" / "all" (défaut "all")
     *  - status    : "in_progress" / "completed" / "failed" / "cancelled" /
     *                "ended" (= COMPLETED+FAILED+CANCELLED) / "all"
     *  - q         : sous-chaîne cherchée dans fileName / fromMachineId / toMachineId
     */
    @GetMapping("/history/{key}")
    public ApiResponse<List<FileTransferHistoryEntry>> getHistory(
            @PathVariable String key,
            @RequestParam(name = "direction", required = false) String direction,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "q", required = false) String search) {

        return ApiResponse.success(
                service.getHistory(key, direction, status, search)
        );
    }
}
