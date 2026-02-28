package com.lumiere.transport.remoteitsupportserver.agent.scheduler;

import com.lumiere.transport.remoteitsupportserver.agent.service.AgentPresenceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component

public class AgentHeartbeatScheduler {
    private final AgentPresenceService agentPresenceService;

    @Value("${agent.heartbeat.timeout:30}") // seconds
    private long heartbeatTimeoutSeconds;

    public AgentHeartbeatScheduler(AgentPresenceService agentPresenceService) {
        this.agentPresenceService = agentPresenceService;
    }

    @Scheduled(fixedRateString = "${agent.scheduler.rate:10000}") // ms
    public void markOfflineIfNoHeartbeat() {
        int updated = agentPresenceService.autoMarkOfflineAgents(heartbeatTimeoutSeconds);

        if (updated > 0) {
            System.out.println("⏱️ Agents auto-OFFLINE : " + updated);
        }
    }
}
