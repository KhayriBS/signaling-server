package com.lumiere.transport.remoteitsupportserver.session.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ApprovalDecisionRequest {
    private boolean allowRemoteInput = true;
    private boolean allowFileTransfer = true;
}
