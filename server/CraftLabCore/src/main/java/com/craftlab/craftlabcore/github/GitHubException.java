package com.craftlab.craftlabcore.github;

/**
 * Exception typée pour toute erreur liée à GitHub (réseau, HTTP, contenu illisible).
 * Toujours capturée et traduite en ImportResult par GitHubModImporter : ne doit jamais
 * remonter jusqu'au serveur Minecraft sans être gérée.
 */
public class GitHubException extends RuntimeException {

    public enum Reason {
        NOT_FOUND,
        RATE_LIMITED,
        NETWORK_ERROR,
        API_ERROR,
        INVALID_CONTENT
    }

    private final Reason reason;

    public GitHubException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
