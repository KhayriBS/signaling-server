package com.lumiere.transport.remoteitsupportserver.session.model;

import com.lumiere.transport.remoteitsupportserver.session.entity.ControlSession;
import com.lumiere.transport.remoteitsupportserver.session.entity.SessionStatus;

import java.time.Duration;
import java.time.Instant;

/**
 * Vue de lecture d'une session pour l'écran "Historique des sessions".
 *
 * Le champ {@code direction} est calculé en fonction de la machine appelante :
 * "incoming" si la session vise cette machine comme agent, "outgoing" si la
 * machine est l'initiatrice (technicianUsername == machineId).
 */
public record SessionHistoryEntry(
        Long id,
        String agentMachineId,
        String technicianUsername,
        String technicianRole,
        SessionStatus status,
        String direction,
        Instant startedAt,
        Instant endedAt,
        Long durationMs
) {
    public static SessionHistoryEntry fromEntity(ControlSession session, String machineId) {
        String direction;
        if (machineId != null && machineId.equals(session.getAgentMachineId())) {
            direction = "incoming";
        } else if (machineId != null && machineId.equals(session.getTechnicianUsername())) {
            direction = "outgoing";
        } else {
            direction = "incoming";
        }

        Long durationMs = null;
        Instant start = session.getStartedAt();
        Instant end = session.getEndedAt();
        if (start != null && end != null) {
            durationMs = Duration.between(start, end).toMillis();
        }

        return new SessionHistoryEntry(
                session.getId(),
                session.getAgentMachineId(),
                session.getTechnicianUsername(),
                session.getTechnicianRole(),
                session.getStatus(),
                direction,
                start,
                end,
                durationMs
        );
    }
}
