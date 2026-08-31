package com.craftlab.launcher.modpack;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public class HttpModPackProvider implements ModPackProvider {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final long OVERALL_TIMEOUT_SECONDS = 20;

    private final String url;
    private final HttpClient httpClient;

    public HttpModPackProvider(String url) {
        this.url = url;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    @Override
    public CompletableFuture<RemoteModPack> getCurrentModPack() {
        // Tout ce bloc est protégé par try/catch : URI.create(...) ou httpClient.sendAsync(...)
        // peuvent lever une exception de façon SYNCHRONE (ex. schéma d'URI non http/https, cas
        // d'un chemin local passé ici par erreur). Une telle exception ne doit jamais sortir de
        // cette méthode autrement que via le CompletableFuture retourné, sinon un appelant qui
        // enchaîne .thenApply/.whenComplete sur le résultat perd l'erreur silencieusement.
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                // Filet de sécurité supplémentaire : borne la durée totale de l'appel même si
                // une opération réseau était déclenchée involontairement (mauvaise détection de
                // source, DNS qui traîne, etc.) plutôt que de rester bloqué indéfiniment.
                .orTimeout(OVERALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new ModPackFetchException("Le serveur ModPack a répondu avec le code " + response.statusCode() + ".");
                    }
                    return ModPackJson.parse(response.body());
                })
                // Un échec ASYNCHRONE (connexion refusée, DNS, timeout déclenché par orTimeout()
                // ci-dessus) ne passe jamais par le catch synchrone plus bas : sans ce filet, ces
                // cas fuiraient sous leur type brut (ConnectException, TimeoutException...) au lieu
                // d'être rapportés comme les autres échecs via ModPackFetchException.
                .exceptionally(throwable -> {
                    Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                        ? throwable.getCause() : throwable;
                    if (cause instanceof ModPackFetchException fetchException) {
                        throw fetchException;
                    }
                    throw new ModPackFetchException(
                        "Impossible de récupérer le ModPack depuis '" + url + "' : " + cause.getMessage());
                });
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(
                new ModPackFetchException("Impossible d'effectuer la requête vers '" + url + "' : " + e.getMessage())
            );
        }
    }
}
