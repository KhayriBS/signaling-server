package com.lumiere.transport.remoteitsupportserver.admin.dto;

import com.lumiere.transport.remoteitsupportserver.agent.entity.Agent;
import com.lumiere.transport.remoteitsupportserver.session.entity.ControlSession;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class AdminDashboardResponse {
    private List<Agent> machines;
    private List<ControlSession> activeSessions;
    private Stats stats;

    @Getter
    @Setter
    @AllArgsConstructor
    public static class Stats {
        private long totalMachines;
        private long onlineMachines;
        private long offlineMachines;
        private long unassignedMachines;
        private long activeSessions;
        private long totalUsers;
    }
}
