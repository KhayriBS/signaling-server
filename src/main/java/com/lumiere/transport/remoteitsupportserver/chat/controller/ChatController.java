package com.lumiere.transport.remoteitsupportserver.chat.controller;

import com.lumiere.transport.remoteitsupportserver.chat.dto.ChatMessageDto;
import com.lumiere.transport.remoteitsupportserver.chat.dto.SendMessageRequest;
import com.lumiere.transport.remoteitsupportserver.chat.entity.ChatMessage;
import com.lumiere.transport.remoteitsupportserver.chat.service.ChatService;
import com.lumiere.transport.remoteitsupportserver.common.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/chat")
public class ChatController {
    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/send/{sessionId}")
    public ResponseEntity<ApiResponse<ChatMessageDto>> sendMessage(
            @PathVariable Long sessionId,
            @RequestBody SendMessageRequest request,
            Authentication authentication) {
        
        String senderRole = request.getSenderRole();
        String senderName = authentication.getName();
        
        // If sender name is from request, use it (for technician)
        if (request.getSenderName() != null && !request.getSenderName().isEmpty()) {
            senderName = request.getSenderName();
        }

        chatService.sendChatMessage(sessionId, senderRole, senderName, request.getContent());
        
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/messages/{sessionId}")
    public ResponseEntity<ApiResponse<List<ChatMessageDto>>> getMessages(
            @PathVariable Long sessionId) {
        
        List<ChatMessage> messages = chatService.getMessages(sessionId);
        List<ChatMessageDto> dtos = messages.stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    @GetMapping("/pending/{sessionId}")
    public ResponseEntity<ApiResponse<List<ChatMessageDto>>> getPendingMessages(
            @PathVariable Long sessionId) {
        
        List<ChatMessage> messages = chatService.getPendingMessages(sessionId);
        List<ChatMessageDto> dtos = messages.stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    private ChatMessageDto toDto(ChatMessage msg) {
        ChatMessageDto dto = new ChatMessageDto();
        dto.setId(msg.getId());
        dto.setSessionId(msg.getSessionId());
        dto.setSenderRole(msg.getSenderRole());
        dto.setSenderName(msg.getSenderName());
        dto.setContent(msg.getContent());
        dto.setTimestamp(msg.getTimestamp().toString());
        dto.setDelivered(msg.isDelivered());
        return dto;
    }
}
