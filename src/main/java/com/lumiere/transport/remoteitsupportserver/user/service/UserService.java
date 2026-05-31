package com.lumiere.transport.remoteitsupportserver.user.service;

import com.lumiere.transport.remoteitsupportserver.agent.repository.AgentRepository;
import com.lumiere.transport.remoteitsupportserver.user.dto.UserUpsertRequest;
import com.lumiere.transport.remoteitsupportserver.user.entity.Role;
import com.lumiere.transport.remoteitsupportserver.user.entity.User;
import com.lumiere.transport.remoteitsupportserver.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AgentRepository agentRepository;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       AgentRepository agentRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.agentRepository = agentRepository;
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public User getById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
    }

    public User create(UserUpsertRequest req) {
        if (isBlank(req.getUsername())) {
            throw new IllegalArgumentException("username is required");
        }
        if (userRepository.existsByUsername(req.getUsername())) {
            throw new IllegalArgumentException("Username already taken: " + req.getUsername());
        }
        if (!isBlank(req.getEmail()) && userRepository.existsByEmail(req.getEmail())) {
            throw new IllegalArgumentException("Email already registered: " + req.getEmail());
        }

        User u = new User();
        u.setUsername(req.getUsername().trim());
        u.setEmail(blankToNull(req.getEmail()));
        u.setFullName(blankToNull(req.getFullName()));
        u.setPhoneNumber(blankToNull(req.getPhoneNumber()));
        u.setDepartment(blankToNull(req.getDepartment()));
        u.setRole(req.getRole() == null ? Role.USER : req.getRole());
        u.setEnabled(req.getEnabled() == null || req.getEnabled());
        // USER : aucun login manuel → pas de password ; ADMIN : on encode
        // si fourni, sinon null (mais sans password l'ADMIN ne peut pas se
        // connecter manuellement, ce qui est acceptable côté agent-only).
        if (!isBlank(req.getPassword())) {
            u.setPassword(passwordEncoder.encode(req.getPassword()));
        }
        return userRepository.save(u);
    }

    public User update(Long id, UserUpsertRequest req) {
        User u = getById(id);

        if (!isBlank(req.getUsername()) && !req.getUsername().equals(u.getUsername())) {
            if (userRepository.existsByUsername(req.getUsername())) {
                throw new IllegalArgumentException("Username already taken: " + req.getUsername());
            }
            u.setUsername(req.getUsername().trim());
        }
        if (req.getEmail() != null) {
            String newEmail = blankToNull(req.getEmail());
            if (newEmail != null && !newEmail.equals(u.getEmail()) && userRepository.existsByEmail(newEmail)) {
                throw new IllegalArgumentException("Email already registered: " + newEmail);
            }
            u.setEmail(newEmail);
        }
        if (req.getFullName() != null)    u.setFullName(blankToNull(req.getFullName()));
        if (req.getPhoneNumber() != null) u.setPhoneNumber(blankToNull(req.getPhoneNumber()));
        if (req.getDepartment() != null)  u.setDepartment(blankToNull(req.getDepartment()));
        if (req.getRole() != null)        u.setRole(req.getRole());
        if (req.getEnabled() != null)     u.setEnabled(req.getEnabled());
        if (!isBlank(req.getPassword()))  u.setPassword(passwordEncoder.encode(req.getPassword()));

        return userRepository.save(u);
    }

    public void delete(Long id) {
        User u = getById(id);
        // On désaffecte toutes les machines liées avant le delete pour ne pas
        // laisser de FK orpheline côté agents.assigned_username.
        agentRepository.findByAssignedUsername(u.getUsername()).forEach(a -> {
            a.setAssignedUsername(null);
            a.setAssignedAt(null);
            a.setAssignedBy(null);
            agentRepository.save(a);
        });
        userRepository.delete(u);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String blankToNull(String s) { return isBlank(s) ? null : s.trim(); }
}
