package com.lumiere.transport.remoteitsupportserver.agent.repository;

import com.lumiere.transport.remoteitsupportserver.agent.entity.AgentMetrics;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentMetricsRepository extends JpaRepository<AgentMetrics, Long> {
    List<AgentMetrics> findTop50ByMachineIdOrderByCreatedAtDesc(String machineId);

}
