package com.lumiere.transport.remoteitsupportserver.ai.repository;

import com.lumiere.transport.remoteitsupportserver.ai.entity.AiSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiSessionRepository extends JpaRepository<AiSession, Long> {
    List<AiSession> findBySessionIdOrderByCreatedAtDesc(String sessionId);
}
