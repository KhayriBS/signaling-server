package com.lumiere.transport.remoteitsupportserver.chat.dto;

public class SendMessageRequest {
    private String senderRole;
    private String senderName;
    private String content;

    public String getSenderRole() { return senderRole; }
    public void setSenderRole(String senderRole) { this.senderRole = senderRole; }

    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
