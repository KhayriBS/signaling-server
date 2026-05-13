package com.lumiere.transport.remoteitsupportserver.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
public class StompWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Limite de taille d'un message STOMP. Defaut Spring = 64 KB, ce qui est
     * trop bas pour les payloads IA (frame JPEG base64 d'un 1080p ~= 200-500 KB).
     * On monte a 8 MB — large marge sans risquer l'OOM (les 4 workers IA
     * traitent un payload chacun = 32 MB max en flight, OK).
     */
    private static final int STOMP_MAX_MESSAGE_SIZE = 8 * 1024 * 1024;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Préfixe pour les destinations de sortie (subscriptions)
        registry.enableSimpleBroker("/topic", "/queue");
        // Préfixe pour les destinations d'entrée (messages envoyés par le client)
        registry.setApplicationDestinationPrefixes("/app");
        // Préfixe pour les messages privés
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/chat")
                .setAllowedOriginPatterns("*")
                .withSockJS();

        registry.addEndpoint("/ws/chat")
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        // Cote bas du tuyau (frame WebSocket brute) — doit suivre la taille STOMP.
        registration.setMessageSizeLimit(STOMP_MAX_MESSAGE_SIZE);
        registration.setSendBufferSizeLimit(STOMP_MAX_MESSAGE_SIZE);
        // 30s pour eviter qu'un client lent ne se fasse couper en plein envoi
        // de screenshot.
        registration.setSendTimeLimit(30_000);
    }
}
