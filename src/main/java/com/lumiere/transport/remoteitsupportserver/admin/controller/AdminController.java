package com.lumiere.transport.remoteitsupportserver.admin.controller;

import com.lumiere.transport.remoteitsupportserver.admin.dto.AdminDashboardResponse;
import com.lumiere.transport.remoteitsupportserver.admin.dto.AssignMachineRequest;
import com.lumiere.transport.remoteitsupportserver.agent.entity.Agent;
import com.lumiere.transport.remoteitsupportserver.agent.entity.AgentStatus;
import com.lumiere.transport.remoteitsupportserver.agent.repository.AgentRepository;
import com.lumiere.transport.remoteitsupportserver.agent.service.AgentPresenceService;
import com.lumiere.transport.remoteitsupportserver.session.entity.SessionStatus;
import com.lumiere.transport.remoteitsupportserver.session.repository.ControlSessionRepository;
import com.lumiere.transport.remoteitsupportserver.user.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoints réservés au TECHNICIEN (Role.ADMIN).
 * SecurityConfig verrouille /admin/** sur hasRole("ADMIN").
 */
@RestController
@RequestMapping("/admin")
public class AdminController {
    private final AgentPresenceService agentPresenceService;
    private final AgentRepository agentRepository;
    private final ControlSessionRepository sessionRepository;
    private final UserRepository userRepository;

    public AdminController(AgentPresenceService agentPresenceService,
                           AgentRepository agentRepository,
                           ControlSessionRepository sessionRepository,
                           UserRepository userRepository) {
        this.agentPresenceService = agentPresenceService;
        this.agentRepository = agentRepository;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
    }

    @PutMapping("/machines/{id}/assign")
    public Agent assignMachine(@PathVariable("id") Long agentId,
                               @RequestBody AssignMachineRequest body,
                               Authentication authentication) {
        if (body == null || body.getUserId() == null) {
            throw new IllegalArgumentException("userId is required");
        }
        return agentPresenceService.assignAgentByUserId(agentId, body.getUserId(), authentication);
    }

    @GetMapping("/dashboard")
    public AdminDashboardResponse dashboard() {
        List<Agent> machines = agentRepository.findAll();
        long online = machines.stream().filter(a -> a.getStatus() == AgentStatus.ONLINE).count();
        long unassigned = machines.stream()
                .filter(a -> a.getAssignedUsername() == null || a.getAssignedUsername().isBlank())
                .count();

        AdminDashboardResponse.Stats stats = new AdminDashboardResponse.Stats(
                machines.size(),
                online,
                machines.size() - online,
                unassigned,
                sessionRepository.countByStatus(SessionStatus.ACTIVE),
                userRepository.count()
        );

        return new AdminDashboardResponse(
                machines,
                sessionRepository.findByStatus(SessionStatus.ACTIVE),
                stats
        );
    }
}
