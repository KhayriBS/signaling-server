package com.lumiere.transport.remoteitsupportserver.agent.repository;

import com.lumiere.transport.remoteitsupportserver.agent.entity.Agent;
import com.lumiere.transport.remoteitsupportserver.agent.entity.AgentStatus;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AgentRepository extends JpaRepository<Agent, Long> {
    Optional<Agent> findByMachineId(String machineId);
    Optional<Agent> findByConnectionCode(String connectionCode);
    List<Agent> findByStatus(AgentStatus status);
    List<Agent> findByAssignedUsername(String assignedUsername);
    List<Agent> findByAssignedUsernameAndStatus(String assignedUsername, AgentStatus status);
    @Modifying
    @Transactional
    @Query("""
        update Agent a
        set a.status = com.lumiere.transport.remoteitsupportserver.agent.entity.AgentStatus.OFFLINE
        where a.status = com.lumiere.transport.remoteitsupportserver.agent.entity.AgentStatus.ONLINE
          and a.lastHeartbeat < :limit
    """)
    int bulkMarkOffline(@Param("limit") Instant limit);

}
