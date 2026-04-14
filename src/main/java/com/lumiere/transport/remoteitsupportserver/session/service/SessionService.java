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
import java.util.List;
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

        boolean isAdmin = authentication != null
            && authentication.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));

        // Legacy auth-only flow kept for reference:
        // boolean isAdmin = authentication.getAuthorities().stream()
        //         .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));

        if (authentication == null) {
            String technicianName = "guest-" + machineId;
            return createSession(agent, technicianName, "USER", SessionStatus.PENDING_APPROVAL, false, false);
        }

        if (!isAdmin) {
            String assignedUsername = agent.getAssignedUsername();
            String currentUsername = authentication.getName();
            if (assignedUsername == null || !assignedUsername.equals(currentUsername)) {
                throw new AccessDeniedException("Machine is not assigned to current user");
            }
        }

        String technicianRole = isAdmin ? "ADMIN" : "USER";
        if (isAdmin) {
            return createSession(agent, authentication.getName(), technicianRole, SessionStatus.ACTIVE, true, true);
        }

        return createSession(agent, authentication.getName(), technicianRole, SessionStatus.PENDING_APPROVAL, false, false);
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

        boolean isAdmin = authentication != null
            && authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));

        String technicianName = (authentication != null && authentication.getName() != null)
                ? authentication.getName()
                : "guest-" + connectionCode;

        String technicianRole = isAdmin ? "ADMIN" : "USER";
        if (isAdmin) {
            return createSession(agent, technicianName, technicianRole, SessionStatus.ACTIVE, true, true);
        }

        return createSession(agent, technicianName, technicianRole, SessionStatus.PENDING_APPROVAL, false, false);
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

    public void stopSessionByToken(String signalingToken) {
        if (signalingToken == null || signalingToken.isBlank()) {
            return;
        }

        sessionRepository.findBySignalingTokenAndStatusIn(signalingToken,
                        List.of(SessionStatus.ACTIVE, SessionStatus.PENDING_APPROVAL))
                .ifPresent(session -> stopSession(session.getId()));
    }

    public Optional<ControlSession> getPendingApprovalForMachine(String machineId, Authentication authentication) {
        assertUserCanApproveMachine(machineId, authentication);
        return sessionRepository.findByAgentMachineIdAndStatus(machineId, SessionStatus.PENDING_APPROVAL);
    }

    public Optional<ControlSession> getPendingApprovalForMachinePublic(String machineId) {
        return sessionRepository.findByAgentMachineIdAndStatus(machineId, SessionStatus.PENDING_APPROVAL);
    }

    public void approveSession(Long sessionId,
                               boolean allowRemoteInput,
                               boolean allowFileTransfer,
                               Authentication authentication) {
        ControlSession session = sessionRepository.findById(sessionId).orElseThrow();
        assertUserCanApproveMachine(session.getAgentMachineId(), authentication);

        if (session.getStatus() != SessionStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Session is not awaiting approval");
        }

        session.setAllowRemoteInput(allowRemoteInput);
        session.setAllowFileTransfer(allowFileTransfer);
        session.setStatus(SessionStatus.ACTIVE);
        sessionRepository.save(session);
    }

    public void rejectSession(Long sessionId, Authentication authentication) {
        ControlSession session = sessionRepository.findById(sessionId).orElseThrow();
        assertUserCanApproveMachine(session.getAgentMachineId(), authentication);
        stopSession(sessionId);
    }

    public void approveSessionPublic(Long sessionId,
                                     boolean allowRemoteInput,
                                     boolean allowFileTransfer) {
        ControlSession session = sessionRepository.findById(sessionId).orElseThrow();

        if (session.getStatus() != SessionStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Session is not awaiting approval");
        }

        session.setAllowRemoteInput(allowRemoteInput);
        session.setAllowFileTransfer(allowFileTransfer);
        session.setStatus(SessionStatus.ACTIVE);
        sessionRepository.save(session);
    }

    public void rejectSessionPublic(Long sessionId) {
        ControlSession session = sessionRepository.findById(sessionId).orElseThrow();
        if (session.getStatus() == SessionStatus.PENDING_APPROVAL || session.getStatus() == SessionStatus.ACTIVE) {
            stopSession(sessionId);
        }
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

    public Optional<ControlSession> getSessionByToken(String signalingToken) {
        if (signalingToken == null || signalingToken.isBlank()) {
            return Optional.empty();
        }
        return sessionRepository.findBySignalingToken(signalingToken);
    }

    private ControlSession createSession(Agent agent,
                                         String technicianName,
                                         String technicianRole,
                                         SessionStatus targetStatus,
                                         boolean allowRemoteInput,
                                         boolean allowFileTransfer) {
        sessionRepository.findByAgentMachineIdAndStatusIn(
                agent.getMachineId(), List.of(SessionStatus.ACTIVE, SessionStatus.PENDING_APPROVAL)
        ).ifPresent(existingSession -> {
            if (existingSession.getStatus() == SessionStatus.PENDING_APPROVAL) {
                stopSession(existingSession.getId());
                return;
            }
            throw new IllegalStateException("Agent already in session");
        });

        agent.setStatus(AgentStatus.BUSY);
        agentRepository.save(agent);

        ControlSession session = new ControlSession();
        session.setAgentMachineId(agent.getMachineId());
        session.setTechnicianUsername(technicianName);
        session.setTechnicianRole(technicianRole);
        session.setAllowRemoteInput(allowRemoteInput);
        session.setAllowFileTransfer(allowFileTransfer);
        session.setStatus(targetStatus);
        session.setStartedAt(Instant.now());
        session.setSignalingToken(SessionToken.generate());

        return sessionRepository.save(session);
    }

    private void assertUserCanApproveMachine(String machineId, Authentication authentication) {
        if (authentication == null) {
            throw new AccessDeniedException("Authentication required");
        }

        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (isAdmin) {
            return;
        }

        var agent = agentRepository.findByMachineId(machineId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + machineId));
        String assignedUsername = agent.getAssignedUsername();
        String currentUsername = authentication.getName();

        if (assignedUsername == null || !assignedUsername.equals(currentUsername)) {
            throw new AccessDeniedException("Machine is not assigned to current user");
        }
    }
}
