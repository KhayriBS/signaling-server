package com.lumiere.transport.remoteitsupportserver.agent.ws;
import com.lumiere.transport.remoteitsupportserver.agent.service.AgentPresenceService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Map;

@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {
    private final AgentPresenceService presenceService;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentWebSocketHandler(AgentPresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {

        URI uri = session.getUri();
        if (uri == null) {
            return;
        }

        Map<String, String> params =
                UriComponentsBuilder.fromUri(uri)
                        .build()
                        .getQueryParams()
                        .toSingleValueMap();

        presenceService.registerOrUpdate(
                params.get("machineId"),
                params.get("hostname"),
                params.get("os")
        );
    }

    @Override
    protected void handleTextMessage(WebSocketSession session,
                                     TextMessage message) throws Exception {

        Map<String, String> payload =
                mapper.readValue(message.getPayload(), Map.class);

        if ("heartbeat".equals(payload.get("type"))) {
            presenceService.heartbeat(payload.get("machineId"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session,
                                      CloseStatus status) {

        URI uri = session.getUri();
        if (uri == null) {
            return;
        }

        Map<String, String> params =
                UriComponentsBuilder.fromUri(uri)
                        .build()
                        .getQueryParams()
                        .toSingleValueMap();

        presenceService.markOffline(params.get("machineId"));
    }
}
