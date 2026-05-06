package com.lumiere.transport.remoteitsupportserver.filetransfer.entity;

/**
 * Sens du transfert :
 *  - UPLOAD   = technicien (viewer) → PC distant (agent)
 *  - DOWNLOAD = PC distant (agent)  → technicien (viewer)
 */
public enum FileTransferDirection {
    UPLOAD,
    DOWNLOAD
}
