package com.lumiere.transport.remoteitsupportserver.chat.repository;

import com.lumiere.transport.remoteitsupportserver.chat.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByRoomIdOrderByTimestampAsc(String roomId);
    List<ChatMessage> findByRoomIdAndDeliveredFalseOrderByTimestampAsc(String roomId);
}
