package com.lumiere.transport.remoteitsupportserver.agent.controller;

import com.lumiere.transport.remoteitsupportserver.agent.dto.AgentMetricsDto;
import com.lumiere.transport.remoteitsupportserver.agent.entity.Agent;
import com.lumiere.transport.remoteitsupportserver.agent.service.AgentMetricsService;
import com.lumiere.transport.remoteitsupportserver.agent.service.AgentPresenceService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/agents")
public class AgentController {
    private final AgentPresenceService agentPresenceService;
    private final AgentMetricsService agentMetricsService;

    public AgentController(AgentPresenceService agentPresenceService,AgentMetricsService agentMetricsService) {
        this.agentPresenceService = agentPresenceService;
        this.agentMetricsService = agentMetricsService;
    }
    @PostMapping("/register")
    public Agent registerOrUpdate(
            @RequestParam String machineId,
            @RequestParam String hostname,
            @RequestParam String os
    ) {
        return agentPresenceService.registerOrUpdate(machineId, hostname, os);
    }
    @PostMapping("/login")
    public String agentLogin(@RequestParam String machineId,
                             @RequestParam String os) {
        return agentPresenceService.loginAgent(machineId, os);
    }


    @PostMapping("/heartbeat")
    public void heartbeat(@RequestParam String machineId) {
        agentPresenceService.heartbeat(machineId);
    }


    @PostMapping("/offline")
    public void markOffline(@RequestParam String machineId) {
        agentPresenceService.markOffline(machineId);
    }


    @GetMapping
    public List<Agent> getAllAgents(Authentication authentication) {
        return agentPresenceService.getAllAgents(authentication);
    }


    @GetMapping("/online")
    public List<Agent> getOnlineAgents(Authentication authentication) {
        return agentPresenceService.getOnlineAgents(authentication);
    }

    @PostMapping("/{agentId}/assign/{username}")
    public Agent assignAgent(@PathVariable Long agentId,
                             @PathVariable String username,
                             Authentication authentication) {
        return agentPresenceService.assignAgentToUser(agentId, username, authentication);
    }

    @PostMapping("/{agentId}/unassign")
    public Agent unassignAgent(@PathVariable Long agentId,
                               Authentication authentication) {
        return agentPresenceService.unassignAgent(agentId, authentication);
    }
    @PostMapping("/metrics")
    public void receiveMetrics(@RequestBody AgentMetricsDto dto, Authentication authentication) {
        String machineId = authentication.getName();
        agentMetricsService.saveMetrics(machineId, dto);
        System.out.println(
                "📥 metrics cpu=" + dto.getCpuUsage() +
                        " ram=" + dto.getRamUsage() +
                        " disk=" + dto.getDiskUsage()
        );
    }

    @GetMapping("/metrics/{machineId}")
    public List<AgentMetricsDto> getMetrics(@PathVariable String machineId) {
        return agentMetricsService.getMetricsHistory(machineId);
    }
}
