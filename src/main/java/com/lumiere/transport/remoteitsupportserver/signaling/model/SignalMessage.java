package com.lumiere.transport.remoteitsupportserver.signaling.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SignalMessage {
    private SignalType type;

    // qui envoie (viewer/agent)
    private String from;

    // à qui (viewer/agent)
    private String to;

    // contenu (SDP, ICE...)
    private Object payload;
}
