package com.lumiere.transport.remoteitsupportserver.agent.service;

import com.lumiere.transport.remoteitsupportserver.agent.dto.AgentLoginResponse;
import com.lumiere.transport.remoteitsupportserver.agent.entity.Agent;
import com.lumiere.transport.remoteitsupportserver.agent.entity.AgentStatus;
import com.lumiere.transport.remoteitsupportserver.agent.repository.AgentRepository;
import com.lumiere.transport.remoteitsupportserver.auth.security.JwtProvider;
import com.lumiere.transport.remoteitsupportserver.user.entity.Role;
import com.lumiere.transport.remoteitsupportserver.user.entity.User;
import com.lumiere.transport.remoteitsupportserver.user.repository.UserRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Service

public class AgentPresenceService {
    private final AgentRepository agentRepository;
    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;


    public AgentPresenceService(AgentRepository agentRepository,
                                JwtProvider jwtProvider,
                                UserRepository userRepository,
                                SimpMessagingTemplate messagingTemplate) {
        this.agentRepository = agentRepository;
        this.jwtProvider = jwtProvider;
        this.userRepository = userRepository;
        this.messagingTemplate = messagingTemplate;
    }

    /** Pousse l'agent modifié sur /topic/agents pour les dashboards abonnés. */
    private void broadcastAgentUpdate(Agent agent) {
        try {
            messagingTemplate.convertAndSend("/topic/agents", agent);
        } catch (Exception ignored) {
            // Le broadcast est best-effort : un échec n'arrête pas l'opération métier.
        }
    }

    public Agent registerOrUpdate(String machineId,
                                  String hostname,
                                  String os) {

        Agent agent = agentRepository.findByMachineId(machineId)
                .orElseGet(() -> {
                    Agent a = new Agent();
                    a.setMachineId(machineId);
                    return a;
                });

        boolean wasOffline = agent.getStatus() != AgentStatus.ONLINE;
        agent.setHostname(hostname);
        agent.setOs(os);
        agent.setStatus(AgentStatus.ONLINE);
        agent.setLastHeartbeat(Instant.now());
        ensureConnectionCode(agent);

        Agent saved = agentRepository.save(agent);
        if (wasOffline) {
            broadcastAgentUpdate(saved);
        }
        return saved;
    }
    public AgentLoginResponse loginAgent(String machineId, String os, String localIp) {
        Agent agent = agentRepository.findByMachineId(machineId)
                .orElseGet(() -> {
                    Agent a = new Agent();
                    a.setMachineId(machineId);
                    a.setOs(os);
                    a.setStatus(AgentStatus.ONLINE);
                    a.setLastHeartbeat(Instant.now());
                    return a;
                });

        boolean wasOffline = agent.getStatus() != AgentStatus.ONLINE;
        agent.setOs(os);
        agent.setStatus(AgentStatus.ONLINE);
        agent.setLastHeartbeat(Instant.now());
        if (localIp != null && !localIp.isBlank()) {
            agent.setLocalIp(localIp.trim());
        }
        ensureConnectionCode(agent);
        Agent saved = agentRepository.save(agent);
        if (wasOffline) {
            broadcastAgentUpdate(saved);
        }

        var owner = (saved.getAssignedUsername() == null || saved.getAssignedUsername().isBlank())
                ? java.util.Optional.<User>empty()
                : userRepository.findByUsername(saved.getAssignedUsername());

        String ownerSpringRole = owner.map(u -> "ROLE_" + u.getRole().name()).orElse(null);
        String ownerUsername = owner.map(User::getUsername).orElse(null);

        String token = jwtProvider.generateTokenAgent(saved, ownerSpringRole, ownerUsername);
        String role = owner
                .map(u -> u.getRole() == Role.ADMIN ? "TECHNICIAN" : "USER")
                .orElse("PENDING");

        return new AgentLoginResponse(
                token,
                role,
                saved.getMachineId(),
                saved.getAssignedUsername(),
                saved.getConnectionCode()
        );
    }

    public Optional<Agent> findByMachineId(String machineId) {
        return agentRepository.findByMachineId(machineId);
    }

    public List<Agent> getAllAgents(Authentication authentication) {
        if (authentication == null) {
            return agentRepository.findAll();
        }

        if (isAdmin(authentication)) {
            return agentRepository.findAll();
        }
        return agentRepository.findByAssignedUsername(authentication.getName());
    }

    public List<Agent> getOnlineAgents(Authentication authentication) {
        if (authentication == null) {
            return agentRepository.findByStatus(AgentStatus.ONLINE);
        }

        if (isAdmin(authentication)) {
            return agentRepository.findByStatus(AgentStatus.ONLINE);
        }
        return agentRepository.findByAssignedUsernameAndStatus(authentication.getName(), AgentStatus.ONLINE);
    }

    /**
     * Liste les machines attribuées au propriétaire de la machine appelante.
     * Utilisé par la vue USER (/my-machines) qui appelle avec le JWT agent —
     * principal == machineId, donc on remonte au owner via assignedUsername.
     */
    public List<Agent> getMachinesForCallerOwner(Authentication authentication) {
        if (authentication == null) {
            return List.of();
        }
        String callerMachineId = authentication.getName();
        return agentRepository.findByMachineId(callerMachineId)
                .map(Agent::getAssignedUsername)
                .filter(u -> u != null && !u.isBlank())
                .map(agentRepository::findByAssignedUsername)
                .orElseGet(List::of);
    }

    public Agent assignAgentToUser(Long agentId, String username, Authentication authentication) {
        if (!isAdmin(authentication)) {
            throw new AccessDeniedException("Only admins can assign machines");
        }

        userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        agent.setAssignedUsername(username);
        agent.setAssignedAt(Instant.now());
        agent.setAssignedBy(authentication.getName());
        Agent saved = agentRepository.save(agent);
        broadcastAgentUpdate(saved);
        return saved;
    }

    /**
     * Variante numérique demandée par /admin/machines/{id}/assign {userId}.
     * Résout l'User par id, puis délègue à assignAgentToUser pour mutualiser
     * le tampon assignedAt/assignedBy.
     */
    public Agent assignAgentByUserId(Long agentId, Long userId, Authentication authentication) {
        if (!isAdmin(authentication)) {
            throw new AccessDeniedException("Only admins can assign machines");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        return assignAgentToUser(agentId, user.getUsername(), authentication);
    }

    public Agent unassignAgent(Long agentId, Authentication authentication) {
        if (!isAdmin(authentication)) {
            throw new AccessDeniedException("Only admins can unassign machines");
        }

        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        agent.setAssignedUsername(null);
        agent.setAssignedAt(null);
        agent.setAssignedBy(null);
        Agent saved = agentRepository.save(agent);
        broadcastAgentUpdate(saved);
        return saved;
    }

    public void markOffline(String machineId) {
        agentRepository.findByMachineId(machineId).ifPresent(agent -> {
            agent.setStatus(AgentStatus.OFFLINE);
            broadcastAgentUpdate(agentRepository.save(agent));
        });
    }

    public void heartbeat(String machineId) {
        agentRepository.findByMachineId(machineId).ifPresent(agent -> {
            agent.setLastHeartbeat(Instant.now());
            agentRepository.save(agent);
        });
    }
    public int autoMarkOfflineAgents(long heartbeatTimeoutSeconds) {
        Instant limit = Instant.now().minusSeconds(heartbeatTimeoutSeconds);
        return agentRepository.bulkMarkOffline(limit);
    }

    private void ensureConnectionCode(Agent agent) {
        if (agent.getConnectionCode() != null && agent.getConnectionCode().matches("\\d{6}")) {
            return;
        }

        String code;
        do {
            code = String.format("%06d", ThreadLocalRandom.current().nextInt(0, 1_000_000));
        } while (agentRepository.findByConnectionCode(code).isPresent());

        agent.setConnectionCode(code);
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication != null
                && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
