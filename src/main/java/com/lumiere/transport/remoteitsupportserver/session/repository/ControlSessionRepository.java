package com.lumiere.transport.remoteitsupportserver.session.repository;

import com.lumiere.transport.remoteitsupportserver.session.entity.ControlSession;
import com.lumiere.transport.remoteitsupportserver.session.entity.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface ControlSessionRepository extends JpaRepository<ControlSession, Long> {
    Optional<ControlSession> findByAgentMachineIdAndStatus(
            String agentMachineId,
            SessionStatus status
    );
    Optional<ControlSession> findBySignalingToken(String signalingToken);
    Optional<ControlSession> findBySignalingTokenAndStatus(String signalingToken, SessionStatus status);
    Optional<ControlSession> findByAgentMachineIdAndStatusIn(String agentMachineId, List<SessionStatus> statuses);
    Optional<ControlSession> findBySignalingTokenAndStatusIn(String signalingToken, List<SessionStatus> statuses);

    /**
     * Historique unifié des sessions impliquant une machine donnée.
     *
     * Une session est "entrante" pour la machine si elle est l'agent contrôlé
     * (agentMachineId == :machineId). Elle est "sortante" si la machine a
     * initié la session (technicianUsername == :machineId).
     *
     * Filtres optionnels :
     *  - direction : "incoming" (agent), "outgoing" (technicien), ou null = les deux
     *  - statuses  : sous-ensemble de SessionStatus à inclure ; null = tous
     *  - search    : sous-chaîne (case-insensitive) à matcher dans agentMachineId,
     *                technicianUsername ou signalingToken ; null/blank = pas de filtre
     */
    @Query("""
        SELECT s FROM ControlSession s
        WHERE
          (
            (:direction IS NULL)
            OR (:direction = 'incoming' AND s.agentMachineId = :machineId)
            OR (:direction = 'outgoing' AND s.technicianUsername = :machineId)
          )
          AND (
            :direction IS NOT NULL
            OR s.agentMachineId = :machineId
            OR s.technicianUsername = :machineId
          )
          AND (:statuses IS NULL OR s.status IN :statuses)
          AND (
            :search IS NULL OR :search = ''
            OR LOWER(s.agentMachineId)     LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(s.technicianUsername) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(s.signalingToken)     LIKE LOWER(CONCAT('%', :search, '%'))
          )
        ORDER BY s.startedAt DESC
    """)
    List<ControlSession> findHistoryForMachine(
            @Param("machineId") String machineId,
            @Param("direction") String direction,
            @Param("statuses") List<SessionStatus> statuses,
            @Param("search") String search
    );
}
