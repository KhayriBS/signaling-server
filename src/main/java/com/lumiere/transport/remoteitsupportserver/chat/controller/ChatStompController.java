package com.lumiere.transport.remoteitsupportserver.chat.controller;

import com.lumiere.transport.remoteitsupportserver.chat.dto.ChatMessageDto;
import com.lumiere.transport.remoteitsupportserver.chat.entity.ChatMessage;
import com.lumiere.transport.remoteitsupportserver.chat.repository.ChatMessageRepository;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Controller
public class ChatStompController {
    
    private final SimpMessagingTemplate messagingTemplate;
    private final ChatMessageRepository chatMessageRepository;

    public ChatStompController(SimpMessagingTemplate messagingTemplate,
                               ChatMessageRepository chatMessageRepository) {
        this.messagingTemplate = messagingTemplate;
        this.chatMessageRepository = chatMessageRepository;
    }

    /**
     * Handle messages sent to /app/chat.send/{sessionId}
     * Broadcast to /topic/chat/{sessionId}
     */
    @MessageMapping("/chat.send/{sessionId}")
    public void sendMessage(@DestinationVariable Long sessionId,
                           @Payload ChatMessageDto message) {
        // Set timestamp if not provided
        if (message.getTimestamp() == null || message.getTimestamp().isEmpty()) {
            message.setTimestamp(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }
        message.setSessionId(sessionId);
        message.setDelivered(true);

        // Save to database
        ChatMessage entity = new ChatMessage(
            sessionId,
            message.getSenderRole(),
            message.getSenderName(),
            message.getReceiverRole(),
            message.getReceiverName(),
            message.getContent()
        );
        ChatMessage saved = chatMessageRepository.save(entity);
        message.setId(saved.getId());
        message.setTimestamp(saved.getTimestamp().toString());
        message.setReceiverRole(saved.getReceiverRole());
        message.setReceiverName(saved.getReceiverName());

        // Broadcast to all subscribers of this session's chat topic
        messagingTemplate.convertAndSend("/topic/chat/" + sessionId, message);
    }

    /**
     * Handle typing notifications
     */
    @MessageMapping("/chat.typing/{sessionId}")
    public void typing(@DestinationVariable Long sessionId,
                      @Payload TypingNotification notification) {
        messagingTemplate.convertAndSend("/topic/chat/" + sessionId + "/typing", notification);
    }

    /**
     * Typing notification DTO
     */
    public static class TypingNotification {
        private String senderName;
        private String senderRole;
        private boolean isTyping;

        public String getSenderName() { return senderName; }
        public void setSenderName(String senderName) { this.senderName = senderName; }

        public String getSenderRole() { return senderRole; }
        public void setSenderRole(String senderRole) { this.senderRole = senderRole; }

        public boolean isTyping() { return isTyping; }
        public void setTyping(boolean typing) { isTyping = typing; }
    }
}
