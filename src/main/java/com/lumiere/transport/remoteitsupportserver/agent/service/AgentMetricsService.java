package com.lumiere.transport.remoteitsupportserver.agent.service;

import com.lumiere.transport.remoteitsupportserver.agent.dto.AgentMetricsDto;
import com.lumiere.transport.remoteitsupportserver.agent.entity.AgentMetrics;
import com.lumiere.transport.remoteitsupportserver.agent.repository.AgentMetricsRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
@Service

public class AgentMetricsService {

    private final AgentMetricsRepository repo;

    public AgentMetricsService(AgentMetricsRepository repo) {
        this.repo = repo;
    }

    public void saveMetrics(String machineId, AgentMetricsDto dto) {
        AgentMetrics m = new AgentMetrics();
        m.setMachineId(machineId);
        m.setCpuUsage(dto.getCpuUsage());
        m.setRamUsage(dto.getRamUsage());
        m.setDiskUsage(dto.getDiskUsage());

        Instant ts = (dto.getTimestamp() != null)
                ? Instant.ofEpochMilli(dto.getTimestamp())
                : Instant.now();

        m.setCreatedAt(ts);

        repo.save(m);
    }
}
