package com.craftlab.launcher.auth.microsoft;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reçoit la redirection du navigateur système une fois l'utilisateur connecté sur la vraie page
 * Microsoft — jamais un navigateur embarqué dans l'application, jamais de mot de passe visible
 * par le launcher. Écoute uniquement sur 127.0.0.1 (jamais accessible depuis le réseau), sur un
 * port éphémère choisi par l'OS pour éviter tout conflit.
 *
 * C'est la méthode que Microsoft documente pour un client "public" desktop :
 * https://learn.microsoft.com/en-us/entra/identity-platform/scenario-desktop-acquire-token
 * ("loopback" redirect URI, ex. http://127.0.0.1:PORT/callback) plutôt que le device code flow
 * (toujours supporté, mais avec une UX plus lourde : recopier un code sur une autre page) — c'est
 * ce choix qui permet le flux "navigateur -> retour automatique au launcher" demandé, sans action
 * manuelle de recopie de code.
 */
final class LoopbackRedirectServer implements AutoCloseable {

    private static final String CALLBACK_PATH = "/callback";

    private final HttpServer server;
    private final CompletableFuture<String> authorizationCode = new CompletableFuture<>();

    private LoopbackRedirectServer(HttpServer server) {
        this.server = server;
    }

    static LoopbackRedirectServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        LoopbackRedirectServer instance = new LoopbackRedirectServer(server);
        server.createContext(CALLBACK_PATH, instance::handle);
        server.start();
        return instance;
    }

    String redirectUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + CALLBACK_PATH;
    }

    /** Bloque jusqu'à réception du code (ou d'un refus/erreur), ou lève après le délai indiqué. */
    String awaitAuthorizationCode(Duration timeout) throws MicrosoftAuthException {
        try {
            return authorizationCode.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new MicrosoftAuthException("Connexion Microsoft non terminée à temps ("
                + timeout.toSeconds() + " s) — aucune redirection reçue du navigateur.");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof MicrosoftAuthException mae) {
                throw mae;
            }
            throw new MicrosoftAuthException("Échec de la réception de la redirection Microsoft : " + cause.getMessage(), cause);
        }
    }

    private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI());
        String body;
        if (params.containsKey("code")) {
            authorizationCode.complete(params.get("code"));
            body = "<html><body><h2>CraftLab</h2><p>Connexion réussie. Vous pouvez fermer cette fenêtre "
                + "et revenir au launcher.</p></body></html>";
        } else {
            String error = params.getOrDefault("error_description", params.getOrDefault("error", "raison inconnue"));
            authorizationCode.completeExceptionally(
                new MicrosoftAuthException("Connexion Microsoft refusée ou annulée : " + error));
            body = "<html><body><h2>CraftLab</h2><p>La connexion a échoué : " + escapeHtml(error)
                + "</p><p>Vous pouvez fermer cette fenêtre et réessayer depuis le launcher.</p></body></html>";
        }

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return result;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
