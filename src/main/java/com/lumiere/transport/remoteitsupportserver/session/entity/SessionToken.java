package com.lumiere.transport.remoteitsupportserver.session.entity;

import java.util.UUID;

public class SessionToken {
    public static String generate() {
        return UUID.randomUUID().toString();
    }
}
