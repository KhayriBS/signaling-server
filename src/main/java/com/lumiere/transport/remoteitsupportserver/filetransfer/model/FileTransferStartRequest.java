package com.lumiere.transport.remoteitsupportserver.filetransfer.model;

import com.lumiere.transport.remoteitsupportserver.filetransfer.entity.FileTransferDirection;

/**
 * Payload envoyé par le client au début d'un transfert P2P.
 * Idempotent : si {@code transferId} existe déjà, on met à jour la ligne
 * existante au lieu d'en créer une nouvelle.
 */
public record FileTransferStartRequest(
        String transferId,
        Long sessionId,
        String fromMachineId,
        String toMachineId,
        FileTransferDirection direction,
        String fileName,
        Long fileSize,
        String mimeType,
        String destPath
) {}
