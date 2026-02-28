package com.lumiere.transport.remoteitsupportserver.agent.entity;
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
@Table(name = "agents")
public class Agent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(unique = true, nullable = false)
    private String machineId;
    private String hostname;
    private String os;
    @Enumerated(EnumType.STRING)
    private AgentStatus status;
    private Instant lastHeartbeat;

}
