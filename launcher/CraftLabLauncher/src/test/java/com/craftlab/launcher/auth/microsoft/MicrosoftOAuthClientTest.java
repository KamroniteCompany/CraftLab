package com.craftlab.launcher.auth.microsoft;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contre un vrai serveur HTTP local simulant l'endpoint /token d'Entra ID — succès, refus, réponse malformée, réseau injoignable. */
class MicrosoftOAuthClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(String responseJson, int statusCode) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/token", exchange -> {
            byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Test
    void exchangeAuthorizationCode_success() throws Exception {
        String authority = startServer(
            "{\"access_token\":\"msa-access-token\",\"refresh_token\":\"msa-refresh-token\",\"expires_in\":3600}", 200);
        MicrosoftOAuthClient client = new MicrosoftOAuthClient("test-client-id", authority, HttpClient.newHttpClient());

        MicrosoftTokenResponse response = client.exchangeAuthorizationCode("auth-code", "http://127.0.0.1:1234/callback", "verifier");

        assertEquals("msa-access-token", response.accessToken());
        assertEquals("msa-refresh-token", response.refreshToken());
        assertEquals(3600L, response.expiresInSeconds());
    }

    @Test
    void refresh_success() throws Exception {
        String authority = startServer(
            "{\"access_token\":\"new-access-token\",\"refresh_token\":\"new-refresh-token\",\"expires_in\":3600}", 200);
        MicrosoftOAuthClient client = new MicrosoftOAuthClient("test-client-id", authority, HttpClient.newHttpClient());

        MicrosoftTokenResponse response = client.refresh("old-refresh-token");

        assertEquals("new-access-token", response.accessToken());
    }

    @Test
    void expiredRefreshToken_throwsWithMicrosoftsErrorDescription() throws Exception {
        String authority = startServer(
            "{\"error\":\"invalid_grant\",\"error_description\":\"AADSTS70008: The refresh token has expired.\"}", 400);
        MicrosoftOAuthClient client = new MicrosoftOAuthClient("test-client-id", authority, HttpClient.newHttpClient());

        MicrosoftAuthException thrown = assertThrows(MicrosoftAuthException.class, () -> client.refresh("expired-token"));
        assertTrue(thrown.getMessage().contains("expired"));
    }

    @Test
    void malformedJson_throwsCleanly() throws Exception {
        String authority = startServer("{ not json", 200);
        MicrosoftOAuthClient client = new MicrosoftOAuthClient("test-client-id", authority, HttpClient.newHttpClient());

        assertThrows(MicrosoftAuthException.class, () -> client.refresh("token"));
    }

    @Test
    void unreachableAuthority_throwsCleanly() {
        MicrosoftOAuthClient client = new MicrosoftOAuthClient("test-client-id", "http://localhost:1", HttpClient.newHttpClient());

        assertThrows(MicrosoftAuthException.class, () -> client.refresh("token"));
    }

    @Test
    void authorizeUrl_containsPkceAndRequiredParameters() {
        MicrosoftOAuthClient client = new MicrosoftOAuthClient("test-client-id");

        String url = client.buildAuthorizeUrl("http://127.0.0.1:5000/callback", "the-challenge", "the-state");

        assertTrue(url.contains("client_id=test-client-id"));
        assertTrue(url.contains("code_challenge=the-challenge"));
        assertTrue(url.contains("code_challenge_method=S256"));
        assertTrue(url.contains("response_type=code"));
        assertTrue(url.contains("state=the-state"));
    }
}
