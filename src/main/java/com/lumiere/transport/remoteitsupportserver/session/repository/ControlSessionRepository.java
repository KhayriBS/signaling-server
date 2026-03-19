package com.lumiere.transport.remoteitsupportserver.session.repository;

import com.lumiere.transport.remoteitsupportserver.session.entity.ControlSession;
import com.lumiere.transport.remoteitsupportserver.session.entity.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
