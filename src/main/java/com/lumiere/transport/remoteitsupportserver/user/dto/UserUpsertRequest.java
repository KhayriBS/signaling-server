package com.lumiere.transport.remoteitsupportserver.user.dto;

import com.lumiere.transport.remoteitsupportserver.user.entity.Role;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Payload de création / mise à jour d'un user par le technicien.
 *
 * Le password est optionnel : pour un USER (jamais de login manuel) on le
 * laisse vide ; pour un ADMIN on le fournit pour permettre /auth/login.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserUpsertRequest {
    private String username;
    private String email;
    private String fullName;
    private String phoneNumber;
    private String department;
    private Role role;
    private Boolean enabled;
    private String password;
}
