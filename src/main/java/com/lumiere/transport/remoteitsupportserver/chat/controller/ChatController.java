package com.lumiere.transport.remoteitsupportserver.chat.controller;

import com.lumiere.transport.remoteitsupportserver.chat.dto.ChatMessageDto;
import com.lumiere.transport.remoteitsupportserver.chat.dto.SendMessageRequest;
import com.lumiere.transport.remoteitsupportserver.chat.entity.ChatMessage;
import com.lumiere.transport.remoteitsupportserver.chat.service.ChatService;
import com.lumiere.transport.remoteitsupportserver.common.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
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

    @PostMapping("/send/{roomId}")
    public ResponseEntity<ApiResponse<ChatMessageDto>> sendMessage(
            @PathVariable String roomId,
            @RequestBody SendMessageRequest request) {

        String senderRole = request.getSenderRole() == null || request.getSenderRole().isBlank()
                ? "viewer"
                : request.getSenderRole();
        String senderName = request.getSenderName() == null || request.getSenderName().isBlank()
                ? "Utilisateur"
                : request.getSenderName();

        chatService.sendChatMessage(
            roomId,
            senderRole,
            senderName,
            request.getReceiverRole(),
            request.getReceiverName(),
            request.getContent()
        );
        
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/messages/{roomId}")
    public ResponseEntity<ApiResponse<List<ChatMessageDto>>> getMessages(
            @PathVariable String roomId) {

        List<ChatMessage> messages = chatService.getMessages(roomId);
        List<ChatMessageDto> dtos = messages.stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    @GetMapping("/pending/{roomId}")
    public ResponseEntity<ApiResponse<List<ChatMessageDto>>> getPendingMessages(
            @PathVariable String roomId) {

        List<ChatMessage> messages = chatService.getPendingMessages(roomId);
        List<ChatMessageDto> dtos = messages.stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    private ChatMessageDto toDto(ChatMessage msg) {
        ChatMessageDto dto = new ChatMessageDto();
        dto.setId(msg.getId());
        dto.setRoomId(msg.getRoomId());
        dto.setSenderRole(msg.getSenderRole());
        dto.setSenderName(msg.getSenderName());
        dto.setReceiverRole(msg.getReceiverRole());
        dto.setReceiverName(msg.getReceiverName());
        dto.setContent(msg.getContent());
        dto.setTimestamp(msg.getTimestamp().toString());
        dto.setDelivered(msg.isDelivered());
        return dto;
    }
}
