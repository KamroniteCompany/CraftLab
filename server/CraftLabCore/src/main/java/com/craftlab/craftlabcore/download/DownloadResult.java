package com.craftlab.craftlabcore.download;

import java.nio.file.Path;

/** Résultat d'un ensureDownloaded() : succès (téléchargé ou déjà valide) ou échec avec raison précise. */
public final class DownloadResult {

    public enum Status {
        DOWNLOADED,
        SKIPPED_ALREADY_VALID,
        REJECTED_TOO_LARGE,
        REJECTED_INVALID_URL,
        HASH_MISMATCH,
        NETWORK_ERROR,
        INSUFFICIENT_DISK_SPACE,
        IO_ERROR
    }

    private final Status status;
    private final String message;
    private final Path path;
    private final String sha256;
    private final long sizeBytes;

    private DownloadResult(Status status, String message, Path path, String sha256, long sizeBytes) {
        this.status = status;
        this.message = message;
        this.path = path;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
    }

    public static DownloadResult downloaded(Path path, String sha256, long sizeBytes) {
        return new DownloadResult(Status.DOWNLOADED, null, path, sha256, sizeBytes);
    }

    public static DownloadResult skipped(Path path, String sha256, long sizeBytes) {
        return new DownloadResult(Status.SKIPPED_ALREADY_VALID, null, path, sha256, sizeBytes);
    }

    public static DownloadResult failure(Status status, String message) {
        return new DownloadResult(status, message, null, null, 0L);
    }

    public boolean isSuccess() {
        return status == Status.DOWNLOADED || status == Status.SKIPPED_ALREADY_VALID;
    }

    public Status getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public Path getPath() {
        return path;
    }

    public String getSha256() {
        return sha256;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }
}
