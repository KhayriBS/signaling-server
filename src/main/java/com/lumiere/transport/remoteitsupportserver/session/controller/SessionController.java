package com.lumiere.transport.remoteitsupportserver.session.controller;

import com.lumiere.transport.remoteitsupportserver.common.dto.ApiResponse;
import com.lumiere.transport.remoteitsupportserver.session.entity.ControlSession;
import com.lumiere.transport.remoteitsupportserver.session.model.ApprovalDecisionRequest;
import com.lumiere.transport.remoteitsupportserver.session.model.SessionHistoryEntry;
import com.lumiere.transport.remoteitsupportserver.session.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    @PostMapping("/stop-by-token/{token}")
    public ApiResponse<Void> stopSessionByToken(
            @PathVariable String token) {

        sessionService.stopSessionByToken(token);
        return ApiResponse.success(null);
    }

    @GetMapping("/approval/{machineId}")
    public ResponseEntity<ControlSession> getPendingApprovalForMachine(
            @PathVariable String machineId,
            Authentication authentication) {

        return sessionService.getPendingApprovalForMachine(machineId, authentication)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/approval-public/{machineId}")
    public ResponseEntity<ControlSession> getPendingApprovalForMachinePublic(
            @PathVariable String machineId) {

        return sessionService.getPendingApprovalForMachinePublic(machineId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/by-token/{token}")
    public ResponseEntity<ControlSession> getSessionByToken(
            @PathVariable String token) {

        return sessionService.getSessionByToken(token)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/approve/{sessionId}")
    public ApiResponse<Void> approveSession(
            @PathVariable Long sessionId,
            @RequestBody ApprovalDecisionRequest request,
            Authentication authentication) {

        sessionService.approveSession(
                sessionId,
                request.isAllowRemoteInput(),
                request.isAllowFileTransfer(),
                authentication
        );
        return ApiResponse.success(null);
    }

    @PostMapping("/reject/{sessionId}")
    public ApiResponse<Void> rejectSession(
            @PathVariable Long sessionId,
            Authentication authentication) {

        sessionService.rejectSession(sessionId, authentication);
        return ApiResponse.success(null);
    }

    @PostMapping("/approve-public/{sessionId}")
    public ApiResponse<Void> approveSessionPublic(
            @PathVariable Long sessionId,
            @RequestBody ApprovalDecisionRequest request) {

        sessionService.approveSessionPublic(
                sessionId,
                request.isAllowRemoteInput(),
                request.isAllowFileTransfer()
        );
        return ApiResponse.success(null);
    }

    @PostMapping("/reject-public/{sessionId}")
    public ApiResponse<Void> rejectSessionPublic(
            @PathVariable Long sessionId) {

        sessionService.rejectSessionPublic(sessionId);
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

    /**
     * Historique des sessions impliquant une machine donnée (côté agent OU côté technicien).
     *
     * Filtres :
     *  - direction : "incoming" / "outgoing" / "all" (défaut "all")
     *  - status    : "active" (= ACTIVE+PENDING_APPROVAL) / "ended" (= TERMINATED) / "all"
     *                ou directement "ACTIVE" / "PENDING_APPROVAL" / "TERMINATED"
     *  - q         : sous-chaîne libre cherchée dans agentMachineId, technicianUsername, signalingToken
     *
     * Exemple : GET /sessions/history/DESKTOP-A4B2C9?direction=outgoing&amp;status=ended&amp;q=LAPTOP
     */
    @GetMapping("/history/{machineId}")
    public ApiResponse<List<SessionHistoryEntry>> getSessionHistory(
            @PathVariable String machineId,
            @RequestParam(name = "direction", required = false) String direction,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "q", required = false) String search) {

        return ApiResponse.success(
                sessionService.getSessionHistory(machineId, direction, status, search)
        );
    }
}
