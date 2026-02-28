package com.lumiere.transport.remoteitsupportserver.agent.service;

import com.lumiere.transport.remoteitsupportserver.agent.entity.Agent;
import com.lumiere.transport.remoteitsupportserver.agent.entity.AgentStatus;
import com.lumiere.transport.remoteitsupportserver.agent.repository.AgentRepository;
import com.lumiere.transport.remoteitsupportserver.auth.security.JwtProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service

public class AgentPresenceService {
    private final AgentRepository agentRepository;
    private final JwtProvider jwtProvider;


    public AgentPresenceService(AgentRepository agentRepository, JwtProvider jwtProvider) {
        this.agentRepository = agentRepository;
        this.jwtProvider = jwtProvider;
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
    public List<Agent> getAllAgents() {
        return agentRepository.findAll();
    }

    public List<Agent> getOnlineAgents() {
        return agentRepository.findByStatus(AgentStatus.ONLINE);
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
}
