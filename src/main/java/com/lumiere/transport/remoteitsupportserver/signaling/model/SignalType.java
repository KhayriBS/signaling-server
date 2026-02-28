package com.lumiere.transport.remoteitsupportserver.signaling.model;

public enum SignalType {
    JOIN,         // viewer/agent rejoint une session
    OFFER,        // SDP offer
    ANSWER,       // SDP answer
    ICE,          // ICE candidate
    LEAVE,        // quitter session
    ERROR
}
