package com.craftlab.craftlabcore.github;

/** Construit et retient les composants GitHub (client HTTP, service, importer) pour toute la durée de vie du serveur. */
public final class GitHubIntegration {

    private static volatile GitHubModImporter importer;

    private GitHubIntegration() {
    }

    public static synchronized void initialize() {
        GitHubClient client = new GitHubClient(GitHubConfig.getToken());
        GitHubService service = new GitHubService(client);
        importer = new GitHubModImporter(service);
    }

    public static GitHubModImporter importer() {
        if (importer == null) {
            initialize();
        }
        return importer;
    }
}
