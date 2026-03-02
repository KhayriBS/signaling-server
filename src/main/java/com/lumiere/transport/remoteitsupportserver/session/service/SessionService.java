package com.lumiere.transport.remoteitsupportserver.session.service;

import com.lumiere.transport.remoteitsupportserver.agent.entity.AgentStatus;
import com.lumiere.transport.remoteitsupportserver.agent.repository.AgentRepository;
import com.lumiere.transport.remoteitsupportserver.session.entity.ControlSession;
import com.lumiere.transport.remoteitsupportserver.session.entity.SessionStatus;
import com.lumiere.transport.remoteitsupportserver.session.entity.SessionToken;
import com.lumiere.transport.remoteitsupportserver.session.repository.ControlSessionRepository;
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

        sessionRepository.findByAgentMachineIdAndStatus(
                machineId, SessionStatus.ACTIVE
        ).ifPresent(s -> {
            throw new IllegalStateException("Agent already in session");
        });

        agentRepository.findByMachineId(machineId).ifPresent(agent -> {
            agent.setStatus(AgentStatus.BUSY);
            agentRepository.save(agent);
        });

        ControlSession session = new ControlSession();
        session.setAgentMachineId(machineId);
        session.setTechnicianUsername(authentication.getName());
        session.setStatus(SessionStatus.ACTIVE);
        session.setStartedAt(Instant.now());

        // 🔐 TOKEN SIGNALING
        session.setSignalingToken(SessionToken.generate());

        return sessionRepository.save(session);
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
    }}
