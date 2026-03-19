package com.lumiere.transport.remoteitsupportserver.session.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "control_sessions")
public class ControlSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(unique = true, nullable = false)
    private String signalingToken;
    private String agentMachineId;
    private String technicianUsername;
    private String technicianRole;
    private boolean allowRemoteInput;
    private boolean allowFileTransfer;
    @Enumerated(EnumType.STRING)
    private SessionStatus status;

    private Instant startedAt;
    private Instant endedAt;


}
