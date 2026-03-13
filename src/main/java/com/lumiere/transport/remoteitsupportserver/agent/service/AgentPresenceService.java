package com.lumiere.transport.remoteitsupportserver.agent.service;

import com.lumiere.transport.remoteitsupportserver.agent.entity.Agent;
import com.lumiere.transport.remoteitsupportserver.agent.entity.AgentStatus;
import com.lumiere.transport.remoteitsupportserver.agent.repository.AgentRepository;
import com.lumiere.transport.remoteitsupportserver.auth.security.JwtProvider;
import com.lumiere.transport.remoteitsupportserver.user.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service

public class AgentPresenceService {
    private final AgentRepository agentRepository;
    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;


    public AgentPresenceService(AgentRepository agentRepository,
                                JwtProvider jwtProvider,
                                UserRepository userRepository) {
        this.agentRepository = agentRepository;
        this.jwtProvider = jwtProvider;
        this.userRepository = userRepository;
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

        agent.setHostname(hostname);
        agent.setOs(os);
        agent.setStatus(AgentStatus.ONLINE);
        agent.setLastHeartbeat(Instant.now());

        return agentRepository.save(agent);
    }
    public String loginAgent(String machineId, String os) {
        Agent agent = agentRepository.findByMachineId(machineId)
                .orElseGet(() -> {
                    Agent a = new Agent();
                    a.setMachineId(machineId);
                    a.setOs(os);
                    a.setStatus(AgentStatus.ONLINE);
                    a.setLastHeartbeat(Instant.now());
                    return a;
                });

        return jwtProvider.generateTokenAgent(agent);
    }
    public List<Agent> getAllAgents(Authentication authentication) {
        if (isAdmin(authentication)) {
            return agentRepository.findAll();
        }
        return agentRepository.findByAssignedUsername(authentication.getName());
    }

    public List<Agent> getOnlineAgents(Authentication authentication) {
        if (isAdmin(authentication)) {
            return agentRepository.findByStatus(AgentStatus.ONLINE);
        }
        return agentRepository.findByAssignedUsernameAndStatus(authentication.getName(), AgentStatus.ONLINE);
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
        return agentRepository.save(agent);
    }

    public Agent unassignAgent(Long agentId, Authentication authentication) {
        if (!isAdmin(authentication)) {
            throw new AccessDeniedException("Only admins can unassign machines");
        }

        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        agent.setAssignedUsername(null);
        return agentRepository.save(agent);
    }

    public void markOffline(String machineId) {
        agentRepository.findByMachineId(machineId).ifPresent(agent -> {
            agent.setStatus(AgentStatus.OFFLINE);
            agentRepository.save(agent);
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

    private boolean isAdmin(Authentication authentication) {
        return authentication != null
                && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
