package com.communityserver.communitytest.download;

/** Signale qu'un flux téléchargé a dépassé la taille maximale autorisée en cours de lecture. */
public class DownloadTooLargeException extends RuntimeException {

    public DownloadTooLargeException(long maxBytes) {
        super("Le téléchargement dépasse la taille maximale autorisée (" + maxBytes + " octets).");
    }
}
