package com.lumiere.transport.remoteitsupportserver.auth.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class MachineAuthStatus {
    private boolean machineExists;
    private boolean hasAssignedUser;
    private String assignedUsername;
}
