package com.lumiere.transport.remoteitsupportserver.session.controller;

import com.lumiere.transport.remoteitsupportserver.common.dto.ApiResponse;
import com.lumiere.transport.remoteitsupportserver.session.entity.ControlSession;
import com.lumiere.transport.remoteitsupportserver.session.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sessions")
public class SessionController {
    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping("/start/{machineId}")
    public ApiResponse<ControlSession> startSession(
            @PathVariable String machineId,
            Authentication authentication) {

        return ApiResponse.success(
                sessionService.startSession(machineId, authentication)
        );
    }

    @PostMapping("/start-by-code/{code}")
    public ApiResponse<ControlSession> startSessionByCode(
            @PathVariable String code,
            Authentication authentication) {

        return ApiResponse.success(
                sessionService.startSessionByCode(code, authentication)
        );
    }

    @PostMapping("/stop/{sessionId}")
    public ApiResponse<Void> stopSession(
            @PathVariable Long sessionId) {

        sessionService.stopSession(sessionId);
        return ApiResponse.success(null);
    }

    /**
     * Récupère la session active en attente pour un agent donné.
     * L'agent appelle cet endpoint pour savoir s'il doit rejoindre une session.
     */
    @GetMapping("/pending/{machineId}")
    public ResponseEntity<ControlSession> getPendingSession(
            @PathVariable String machineId) {

        return sessionService.getActiveSessionForAgent(machineId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }
}
