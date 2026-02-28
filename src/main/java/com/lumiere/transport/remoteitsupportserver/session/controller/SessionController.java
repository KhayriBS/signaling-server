package com.lumiere.transport.remoteitsupportserver.session.controller;

import com.lumiere.transport.remoteitsupportserver.common.dto.ApiResponse;
import com.lumiere.transport.remoteitsupportserver.session.entity.ControlSession;
import com.lumiere.transport.remoteitsupportserver.session.service.SessionService;
import org.springframework.security.core.Authentication;
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

    @PostMapping("/stop/{sessionId}")
    public ApiResponse<Void> stopSession(
            @PathVariable Long sessionId) {

        sessionService.stopSession(sessionId);
        return ApiResponse.success(null);
    }
}
