package com.lumiere.transport.remoteitsupportserver.user.service;

import com.lumiere.transport.remoteitsupportserver.user.entity.User;
import com.lumiere.transport.remoteitsupportserver.user.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserService {
    private final UserRepository userRepository;
    public UserService (UserRepository userRepository) {
        this.userRepository = userRepository;
    }
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

}
