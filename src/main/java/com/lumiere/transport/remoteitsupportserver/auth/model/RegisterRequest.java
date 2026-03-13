package com.lumiere.transport.remoteitsupportserver.auth.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterRequest {
    private String fullName;
    private String email;
    private String password;
    private String phoneNumber;
    private String department;
    private String machineId;
}
