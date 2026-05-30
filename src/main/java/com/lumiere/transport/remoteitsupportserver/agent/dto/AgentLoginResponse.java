package com.lumiere.transport.remoteitsupportserver.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class AgentLoginResponse {
    private String token;
    private String role;
    private String machineId;
    private String assignedUsername;
    private String connectionCode;
}
