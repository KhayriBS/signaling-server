package com.lumiere.transport.remoteitsupportserver.agent.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
@Getter
@Setter
@Entity
@Table(name = "agent_metrics")
public class AgentMetrics {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String machineId;

    @Column(name = "cpu_usage")
    private Double cpuUsage;

    @Column(name = "ram_usage")
    private Double ramUsage;

    @Column(name = "disk_usage")
    private Double diskUsage;

    @Column(nullable = false)
    private Instant createdAt;

}
