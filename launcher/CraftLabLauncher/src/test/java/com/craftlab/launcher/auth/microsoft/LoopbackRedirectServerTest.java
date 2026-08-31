package com.craftlab.launcher.auth.microsoft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simule exactement ce que fait le navigateur système une fois l'utilisateur connecté sur la
 * vraie page Microsoft : une requête GET vers l'URI de redirection avec ?code=... (ou ?error=...
 * en cas de refus). Vrai serveur HTTP local, vraie requête réseau — seule la "vraie connexion
 * Microsoft" en amont est hors de portée d'un test sans compte réel.
 */
class LoopbackRedirectServerTest {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    @Timeout(10)
    void capturesTheAuthorizationCode_fromARealHttpRequest() throws Exception {
        try (LoopbackRedirectServer server = LoopbackRedirectServer.start()) {
            String redirectUri = server.redirectUri();
            assertTrue(redirectUri.startsWith("http://127.0.0.1:"));

            httpClient.send(
                HttpRequest.newBuilder(URI.create(redirectUri + "?code=abc123&state=xyz")).GET().build(),
                HttpResponse.BodyHandlers.discarding());

            String code = server.awaitAuthorizationCode(Duration.ofSeconds(5));

            assertEquals("abc123", code);
        }
    }

    @Test
    @Timeout(10)
    void userDeniedConsent_throwsWithAClearReason() throws Exception {
        try (LoopbackRedirectServer server = LoopbackRedirectServer.start()) {
            String redirectUri = server.redirectUri();

            httpClient.send(
                HttpRequest.newBuilder(URI.create(redirectUri + "?error=access_denied&error_description=User+cancelled")).GET().build(),
                HttpResponse.BodyHandlers.discarding());

            MicrosoftAuthException thrown = assertThrows(MicrosoftAuthException.class,
                () -> server.awaitAuthorizationCode(Duration.ofSeconds(5)));
            assertTrue(thrown.getMessage().contains("User cancelled"));
        }
    }

    @Test
    @Timeout(10)
    void noRequestEver_timesOutWithAClearMessage() throws Exception {
        try (LoopbackRedirectServer server = LoopbackRedirectServer.start()) {
            MicrosoftAuthException thrown = assertThrows(MicrosoftAuthException.class,
                () -> server.awaitAuthorizationCode(Duration.ofMillis(500)));
            assertTrue(thrown.getMessage().toLowerCase().contains("temps") || thrown.getMessage().toLowerCase().contains("délai"));
        }
    }
}
