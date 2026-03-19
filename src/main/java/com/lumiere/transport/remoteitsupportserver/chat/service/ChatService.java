package com.lumiere.transport.remoteitsupportserver.chat.service;

import com.lumiere.transport.remoteitsupportserver.chat.entity.ChatMessage;
import com.lumiere.transport.remoteitsupportserver.chat.repository.ChatMessageRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ChatService {
    private final ChatMessageRepository chatMessageRepository;

    public ChatService(ChatMessageRepository chatMessageRepository) {
        this.chatMessageRepository = chatMessageRepository;
    }

    public ChatMessage saveMessage(String roomId,
                                   String senderRole,
                                   String senderName,
                                   String receiverRole,
                                   String receiverName,
                                   String content) {
        ChatMessage message = new ChatMessage(roomId, senderRole, senderName, receiverRole, receiverName, content);
        return chatMessageRepository.save(message);
    }

    public List<ChatMessage> getMessages(String roomId) {
        return chatMessageRepository.findByRoomIdOrderByTimestampAsc(roomId);
    }

    public void sendChatMessage(String roomId,
                                String senderRole,
                                String senderName,
                                String receiverRole,
                                String receiverName,
                                String content) {
        // Persist only (chat flow is Viewer <-> Backend, not via Windows agent)
        ChatMessage saved = saveMessage(roomId, senderRole, senderName, receiverRole, receiverName, content);
        saved.setDelivered(true);
        chatMessageRepository.save(saved);
    }

    public List<ChatMessage> getPendingMessages(String roomId) {
        return chatMessageRepository.findByRoomIdAndDeliveredFalseOrderByTimestampAsc(roomId);
    }

    public void markAsDelivered(Long messageId) {
        chatMessageRepository.findById(messageId).ifPresent(msg -> {
            msg.setDelivered(true);
            chatMessageRepository.save(msg);
        });
    }
}
