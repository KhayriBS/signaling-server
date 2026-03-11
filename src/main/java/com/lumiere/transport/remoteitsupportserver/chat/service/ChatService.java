package com.lumiere.transport.remoteitsupportserver.chat.service;

import com.lumiere.transport.remoteitsupportserver.chat.entity.ChatMessage;
import com.lumiere.transport.remoteitsupportserver.chat.repository.ChatMessageRepository;
import com.lumiere.transport.remoteitsupportserver.signaling.model.SignalMessage;
import com.lumiere.transport.remoteitsupportserver.signaling.model.SignalType;
import com.lumiere.transport.remoteitsupportserver.signaling.service.SignalingService;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@Service
public class ChatService {
    private final ChatMessageRepository chatMessageRepository;
    private final SignalingService signalingService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatService(ChatMessageRepository chatMessageRepository,
                      SignalingService signalingService) {
        this.chatMessageRepository = chatMessageRepository;
        this.signalingService = signalingService;
    }

    public ChatMessage saveMessage(Long sessionId, String senderRole, String senderName, String content) {
        ChatMessage message = new ChatMessage(sessionId, senderRole, senderName, content);
        return chatMessageRepository.save(message);
    }

    public List<ChatMessage> getMessages(Long sessionId) {
        return chatMessageRepository.findBySessionIdOrderByTimestampAsc(sessionId);
    }

    public void sendChatMessage(Long sessionId, String senderRole, String senderName, String content) {
        // Save to database
        ChatMessage saved = saveMessage(sessionId, senderRole, senderName, content);

        // Determine recipient
        String targetRole = senderRole.equals("viewer") ? "agent" : "viewer";

        // Send via WebSocket
        try {
            WebSocketSession peer = signalingService.getPeer(String.valueOf(sessionId), targetRole);
            if (peer != null && peer.isOpen()) {
                SignalMessage signalMessage = new SignalMessage();
                signalMessage.setType(SignalType.CHAT);
                signalMessage.setFrom(senderRole);
                signalMessage.setTo(targetRole);
                signalMessage.setPayload(Map.of(
                    "id", saved.getId(),
                    "senderName", senderName,
                    "content", content,
                    "timestamp", saved.getTimestamp().toString()
                ));

                peer.sendMessage(new TextMessage(mapper.writeValueAsString(signalMessage)));
                saved.setDelivered(true);
                chatMessageRepository.save(saved);
            }
        } catch (Exception e) {
            System.err.println("Failed to send chat message: " + e.getMessage());
        }
    }

    public List<ChatMessage> getPendingMessages(Long sessionId) {
        return chatMessageRepository.findBySessionIdAndDeliveredFalseOrderByTimestampAsc(sessionId);
    }

    public void markAsDelivered(Long messageId) {
        chatMessageRepository.findById(messageId).ifPresent(msg -> {
            msg.setDelivered(true);
            chatMessageRepository.save(msg);
        });
    }
}
