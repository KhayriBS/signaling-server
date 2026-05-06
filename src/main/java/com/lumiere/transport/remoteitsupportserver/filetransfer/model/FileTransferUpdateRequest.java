package com.lumiere.transport.remoteitsupportserver.filetransfer.model;

import com.lumiere.transport.remoteitsupportserver.filetransfer.entity.FileTransferStatus;

/**
 * Payload envoyé par le client à la fin d'un transfert (ou en cas d'erreur).
 * On accepte uniquement le statut, la taille finale et un éventuel message
 * d'erreur — le reste est figé au START.
 */
public record FileTransferUpdateRequest(
        FileTransferStatus status,
        Long fileSize,
        String errorMessage,
        String destPath
) {}
