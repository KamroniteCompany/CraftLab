package com.craftlab.craftlabcore.github;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Client HTTP bas niveau vers l'API GitHub. Ne connaît rien du domaine (repository/release/mod) :
 * il envoie des requêtes GET et retourne soit le corps JSON brut, soit une GitHubException typée.
 * Utilise java.net.http.HttpClient (JDK 21, aucune dépendance externe) sur un petit pool de
 * threads dédié — jamais le thread principal du serveur Minecraft.
 */
public class GitHubClient {

    private static final String API_BASE = "https://api.github.com";
    private static final String USER_AGENT = "CraftLabCore-Forge-Mod";

    private final HttpClient httpClient;
    /** Optionnel, jamais hardcodé : lu depuis config.properties par GitHubConfig si présent. */
    private final String token;

    public GitHubClient(String token) {
        this.token = token;

        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "CraftLabCore-GitHub-HTTP");
            thread.setDaemon(true);
            return thread;
        };

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(Executors.newFixedThreadPool(2, threadFactory))
            .build();
    }

    /** GET sur un endpoint de l'API REST GitHub (chemin relatif à https://api.github.com). */
    public CompletableFuture<String> getApi(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(API_BASE + path))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", USER_AGENT)
            .GET();

        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }

        return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
            .handle((response, throwable) -> {
                if (throwable != null) {
                    throw new GitHubException(GitHubException.Reason.NETWORK_ERROR, describeNetworkError(throwable));
                }
                return handleResponse(path, response);
            });
    }

    private String handleResponse(String path, HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 200) {
            return response.body();
        }
        if (status == 404) {
            throw new GitHubException(GitHubException.Reason.NOT_FOUND, "Ressource GitHub introuvable : " + path);
        }
        if (status == 403 || status == 429) {
            throw new GitHubException(GitHubException.Reason.RATE_LIMITED, "Limite de requêtes GitHub atteinte.");
        }
        throw new GitHubException(GitHubException.Reason.API_ERROR, "Réponse GitHub inattendue (code " + status + ") pour " + path);
    }

    private String describeNetworkError(Throwable throwable) {
        String message = throwable.getMessage();
        return message != null ? message : throwable.getClass().getSimpleName();
    }
}
