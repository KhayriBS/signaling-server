package com.lumiere.transport.remoteitsupportserver.session.service;

import com.lumiere.transport.remoteitsupportserver.agent.entity.AgentStatus;
import com.lumiere.transport.remoteitsupportserver.agent.entity.Agent;
import com.lumiere.transport.remoteitsupportserver.agent.repository.AgentRepository;
import com.lumiere.transport.remoteitsupportserver.session.entity.ControlSession;
import com.lumiere.transport.remoteitsupportserver.session.entity.SessionStatus;
import com.lumiere.transport.remoteitsupportserver.session.entity.SessionToken;
import com.lumiere.transport.remoteitsupportserver.session.repository.ControlSessionRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
public class SessionService {
    private final ControlSessionRepository sessionRepository;
    private final AgentRepository agentRepository;

    public SessionService(ControlSessionRepository sessionRepository,
                          AgentRepository agentRepository) {
        this.sessionRepository = sessionRepository;
        this.agentRepository = agentRepository;
    }

    public ControlSession startSession(String machineId,
                                       Authentication authentication) {

        var agent = agentRepository.findByMachineId(machineId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + machineId));

        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));

        if (!isAdmin) {
            String assignedUsername = agent.getAssignedUsername();
            String currentUsername = authentication.getName();
            if (assignedUsername == null || !assignedUsername.equals(currentUsername)) {
                throw new AccessDeniedException("Machine is not assigned to current user");
            }
        }

        return createActiveSession(agent, authentication.getName());
    }

    public ControlSession startSessionByCode(String connectionCode,
                                             Authentication authentication) {
        if (connectionCode == null || !connectionCode.matches("\\d{6}")) {
            throw new IllegalArgumentException("Connection code must be exactly 6 digits");
        }

        Agent agent = agentRepository.findByConnectionCode(connectionCode)
                .orElseThrow(() -> new IllegalArgumentException("Invalid connection code"));

        if (agent.getStatus() != AgentStatus.ONLINE) {
            throw new IllegalStateException("Target machine is offline");
        }

        String technicianName = (authentication != null && authentication.getName() != null)
                ? authentication.getName()
                : "guest-" + connectionCode;

        return createActiveSession(agent, technicianName);
    }

    public void stopSession(Long sessionId) {

        ControlSession session = sessionRepository.findById(sessionId)
                .orElseThrow();

        session.setStatus(SessionStatus.TERMINATED);
        session.setEndedAt(Instant.now());
        sessionRepository.save(session);

        // remettre l’agent ONLINE
        agentRepository.findByMachineId(session.getAgentMachineId())
                .ifPresent(agent -> {
                    agent.setStatus(AgentStatus.ONLINE);
                    agentRepository.save(agent);
                });
    }
    /**
     * Récupère la session active pour un agent donné.
     * Utilisé par l'agent pour savoir s'il doit rejoindre une session de signaling.
     */
    public Optional<ControlSession> getActiveSessionForAgent(String machineId) {
        return sessionRepository.findByAgentMachineIdAndStatus(
                machineId,
                SessionStatus.ACTIVE
        );
    }

    private ControlSession createActiveSession(Agent agent, String technicianName) {
        sessionRepository.findByAgentMachineIdAndStatus(
                agent.getMachineId(), SessionStatus.ACTIVE
        ).ifPresent(s -> {
            throw new IllegalStateException("Agent already in session");
        });

        agent.setStatus(AgentStatus.BUSY);
        agentRepository.save(agent);

        ControlSession session = new ControlSession();
        session.setAgentMachineId(agent.getMachineId());
        session.setTechnicianUsername(technicianName);
        session.setStatus(SessionStatus.ACTIVE);
        session.setStartedAt(Instant.now());
        session.setSignalingToken(SessionToken.generate());

        return sessionRepository.save(session);
    }
}
