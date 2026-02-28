package com.lumiere.transport.remoteitsupportserver.agent.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AgentMetricsDto {
    private Double cpuUsage;
    private Double ramUsage;
    private Double diskUsage;
    private Long timestamp;
}
