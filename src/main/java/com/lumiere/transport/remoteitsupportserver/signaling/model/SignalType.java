package com.lumiere.transport.remoteitsupportserver.signaling.model;

public enum SignalType {
    JOIN,         // viewer/agent rejoint une session
    OFFER,        // SDP offer
    ANSWER,       // SDP answer
    ICE,          // ICE candidate
    LEAVE,        // quitter session
    CHAT,         // chat message
    ERROR,
    // File Transfer
    FILE_LIST_REQUEST,   // Request directory listing
    FILE_LIST,           // Directory listing response
    FILE_DOWNLOAD_REQUEST, // Request to download a file
    FILE_UPLOAD_REQUEST,   // Request to upload a file
    FILE_DATA,           // File data chunk
    FILE_COMPLETE,       // File transfer complete
    FILE_ERROR           // File transfer error
}
