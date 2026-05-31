package com.lumiere.transport.remoteitsupportserver.user.entity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(unique = true)
    private String email;

    private String fullName;

    private String phoneNumber;

    private String department;

    // Nullable : les comptes USER sont créés par le technicien et ne se
    // connectent jamais manuellement (le JWT vient de /agents/login via le
    // BIOS serial). Seuls les comptes ADMIN qui utilisent /auth/login en
    // ont besoin.
    @Column
    private String password;

    @Enumerated(EnumType.STRING)
    private Role role;

    private boolean enabled = true;
}
