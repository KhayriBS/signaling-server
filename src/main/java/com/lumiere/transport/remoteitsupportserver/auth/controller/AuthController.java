package com.lumiere.transport.remoteitsupportserver.auth.controller;

import com.lumiere.transport.remoteitsupportserver.auth.model.AuthRequest;
import com.lumiere.transport.remoteitsupportserver.auth.model.MachineAuthStatus;
import com.lumiere.transport.remoteitsupportserver.auth.model.RegisterRequest;
import com.lumiere.transport.remoteitsupportserver.auth.security.JwtProvider;
import com.lumiere.transport.remoteitsupportserver.agent.repository.AgentRepository;
import com.lumiere.transport.remoteitsupportserver.common.dto.ApiResponse;
import com.lumiere.transport.remoteitsupportserver.user.entity.Role;
import com.lumiere.transport.remoteitsupportserver.user.entity.User;
import com.lumiere.transport.remoteitsupportserver.user.repository.UserRepository;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/auth", ""})
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final JwtProvider jwtProvider;
        private final AgentRepository agentRepository;
        private final UserRepository userRepository;
        private final PasswordEncoder passwordEncoder;

    public AuthController(AuthenticationManager authenticationManager,
                                                  JwtProvider jwtProvider,
                                                  AgentRepository agentRepository,
                                                  UserRepository userRepository,
                                                  PasswordEncoder passwordEncoder) {
        this.authenticationManager = authenticationManager;
        this.jwtProvider = jwtProvider;
                this.agentRepository = agentRepository;
                this.userRepository = userRepository;
                this.passwordEncoder = passwordEncoder;
    }

        @GetMapping("/machine-status/{machineId}")
        public ApiResponse<MachineAuthStatus> machineStatus(@PathVariable String machineId) {
                var optionalAgent = agentRepository.findByMachineId(machineId);
                if (optionalAgent.isEmpty()) {
                        return ApiResponse.success(new MachineAuthStatus(false, false, null));
                }

                var agent = optionalAgent.get();
                String assigned = agent.getAssignedUsername();
                boolean hasAssignedUser = assigned != null && !assigned.isBlank();

                return ApiResponse.success(new MachineAuthStatus(true, hasAssignedUser, assigned));
        }

    @PostMapping("/login")
    public ApiResponse<String> login(@RequestBody AuthRequest request) {

                String principal = request.getEmail() != null && !request.getEmail().isBlank()
                                ? request.getEmail()
                                : request.getUsername();

                if (principal == null || principal.isBlank() || request.getPassword() == null || request.getPassword().isBlank()) {
                        throw new IllegalArgumentException("Email/username and password are required");
                }

        Authentication authentication =
                authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(
                                                                principal,
                                request.getPassword()
                        )
                );

        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
                boolean isAdmin = userDetails.getAuthorities().stream()
                                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));

                bindMachineToUser(request.getMachineId(), userDetails.getUsername(), isAdmin);

        String token = jwtProvider.generateToken(userDetails);

        return ApiResponse.success(token);
    }

        @PostMapping("/register")
        public ApiResponse<String> register(@RequestBody RegisterRequest request) {
                if (isBlank(request.getFullName())
                                || isBlank(request.getEmail())
                                || isBlank(request.getPassword())
                                || isBlank(request.getPhoneNumber())
                                || isBlank(request.getDepartment())) {
                        throw new IllegalArgumentException("All registration fields are required");
                }

                if (userRepository.existsByEmail(request.getEmail())) {
                        throw new IllegalArgumentException("Email already registered");
                }

                User user = new User();
                user.setUsername(request.getEmail());
                user.setEmail(request.getEmail());
                user.setFullName(request.getFullName());
                user.setPhoneNumber(request.getPhoneNumber());
                user.setDepartment(request.getDepartment());
                user.setPassword(passwordEncoder.encode(request.getPassword()));
                user.setRole(Role.USER);
                user.setEnabled(true);

                User saved = userRepository.save(user);

                bindMachineToUser(request.getMachineId(), saved.getUsername(), false);

                UserDetails userDetails = org.springframework.security.core.userdetails.User
                                .withUsername(saved.getUsername())
                                .password(saved.getPassword())
                                .roles(saved.getRole().name())
                                .disabled(!saved.isEnabled())
                                .build();

                String token = jwtProvider.generateToken(userDetails);
                return ApiResponse.success(token);
        }

        private void bindMachineToUser(String machineId, String username, boolean isAdmin) {
                if (isBlank(machineId)) {
                        return;
                }

                var agent = agentRepository.findByMachineId(machineId)
                                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + machineId));

                String currentAssigned = agent.getAssignedUsername();
                if (!isAdmin && currentAssigned != null && !currentAssigned.equals(username)) {
                        throw new AccessDeniedException("Machine already assigned to another user");
                }

                agent.setAssignedUsername(username);
                agentRepository.save(agent);
        }

        private boolean isBlank(String value) {
                return value == null || value.isBlank();
        }
}
