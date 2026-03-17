package com.lumiere.transport.remoteitsupportserver.session.repository;

import com.lumiere.transport.remoteitsupportserver.session.entity.ControlSession;
import com.lumiere.transport.remoteitsupportserver.session.entity.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ControlSessionRepository extends JpaRepository<ControlSession, Long> {
    Optional<ControlSession> findByAgentMachineIdAndStatus(
            String agentMachineId,
            SessionStatus status
    );
    Optional<ControlSession> findBySignalingToken(String signalingToken);
    Optional<ControlSession> findBySignalingTokenAndStatus(String signalingToken, SessionStatus status);
}
