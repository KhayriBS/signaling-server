package com.lumiere.transport.remoteitsupportserver.config;

import com.lumiere.transport.remoteitsupportserver.agent.ws.AgentWebSocketHandler;
import com.lumiere.transport.remoteitsupportserver.signaling.ws.SignalingWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {


    private final AgentWebSocketHandler agentHandler;
    private final SignalingWebSocketHandler signalingHandler;


    public WebSocketConfig(AgentWebSocketHandler agentHandler,
                           SignalingWebSocketHandler signalingHandler) {
        this.agentHandler = agentHandler;
        this.signalingHandler = signalingHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentHandler, "/ws/agent")
                .setAllowedOrigins("*");
        registry.addHandler(signalingHandler, "/ws/signaling")
                .setAllowedOrigins("*");
    }
    }

