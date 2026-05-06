package com.lumiere.transport.remoteitsupportserver.filetransfer.repository;

import com.lumiere.transport.remoteitsupportserver.filetransfer.entity.FileTransferDirection;
import com.lumiere.transport.remoteitsupportserver.filetransfer.entity.FileTransferLog;
import com.lumiere.transport.remoteitsupportserver.filetransfer.entity.FileTransferStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FileTransferLogRepository extends JpaRepository<FileTransferLog, Long> {

    Optional<FileTransferLog> findByTransferId(String transferId);

    /**
     * Historique des transferts impliquant une machine donnée (en source OU
     * destination). Filtres optionnels :
     *  - direction : "incoming" (la machine reçoit), "outgoing" (la machine envoie),
     *                ou null = les deux
     *  - statuses  : sous-ensemble de {@link FileTransferStatus} ; null = tous
     *  - search    : sous-chaîne (case-insensitive) cherchée dans fileName,
     *                fromMachineId, toMachineId
     */
    @Query("""
        SELECT f FROM FileTransferLog f
        WHERE
          (
            (:direction IS NULL)
            OR (:direction = 'incoming' AND f.toMachineId   = :machineId)
            OR (:direction = 'outgoing' AND f.fromMachineId = :machineId)
          )
          AND (
            :direction IS NOT NULL
            OR f.fromMachineId = :machineId
            OR f.toMachineId   = :machineId
          )
          AND (:statuses IS NULL OR f.status IN :statuses)
          AND (
            :search IS NULL OR :search = ''
            OR LOWER(f.fileName)      LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(f.fromMachineId) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(f.toMachineId)   LIKE LOWER(CONCAT('%', :search, '%'))
          )
        ORDER BY f.startedAt DESC
    """)
    List<FileTransferLog> findHistoryForMachine(
            @Param("machineId") String machineId,
            @Param("direction") String direction,
            @Param("statuses") List<FileTransferStatus> statuses,
            @Param("search") String search
    );

    List<FileTransferLog> findBySessionIdOrderByStartedAtDesc(Long sessionId);

    List<FileTransferLog> findByFromMachineIdAndDirectionOrderByStartedAtDesc(
            String fromMachineId, FileTransferDirection direction);
}
