package com.craftlab.launcher.auth.microsoft;

import com.craftlab.launcher.auth.AuthSession;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enchaîne réellement les quatre étapes (Microsoft -> Xbox Live -> XSTS -> Minecraft Services)
 * via quatre serveurs HTTP locaux, en utilisant les vraies classes clientes (pas de simulation
 * de la logique métier) — la seule chose qui reste hors de portée sans compte réel est la vraie
 * page de connexion Microsoft elle-même : le "navigateur" est ici remplacé par un appel direct à
 * l'URI de redirection, exactement ce qu'un navigateur ferait après une connexion réussie.
 */
class MicrosoftAuthProviderTest {

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(s -> s.stop(0));
    }

    private HttpServer newServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        servers.add(server);
        return server;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body, int status) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    @Timeout(20)
    void fullInteractiveFlow_producesACorrectAuthSession(@TempDir Path tempDir) throws Exception {
        HttpServer tokenServer = newServer();
        tokenServer.createContext("/token", exchange -> respond(exchange,
            "{\"access_token\":\"msa-token\",\"refresh_token\":\"msa-refresh\",\"expires_in\":3600}", 200));
        tokenServer.start();
        String authority = "http://localhost:" + tokenServer.getAddress().getPort();

        HttpServer xblServer = newServer();
        xblServer.createContext("/xbl", exchange -> respond(exchange,
            "{\"Token\":\"xbl-token\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"the-user-hash\"}]}}", 200));
        xblServer.start();
        String xblEndpoint = "http://localhost:" + xblServer.getAddress().getPort() + "/xbl";

        HttpServer xstsServer = newServer();
        xstsServer.createContext("/xsts", exchange -> respond(exchange,
            "{\"Token\":\"xsts-token\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"the-user-hash\"}]}}", 200));
        xstsServer.start();
        String xstsEndpoint = "http://localhost:" + xstsServer.getAddress().getPort() + "/xsts";

        HttpServer mcServer = newServer();
        mcServer.createContext("/login", exchange -> respond(exchange,
            "{\"access_token\":\"minecraft-access-token\",\"expires_in\":86400}", 200));
        mcServer.createContext("/profile", exchange -> respond(exchange,
            "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}", 200));
        mcServer.start();
        String mcLoginEndpoint = "http://localhost:" + mcServer.getAddress().getPort() + "/login";
        String mcProfileEndpoint = "http://localhost:" + mcServer.getAddress().getPort() + "/profile";

        HttpClient httpClient = HttpClient.newHttpClient();
        MicrosoftAuthProvider provider = new MicrosoftAuthProvider(
            new MicrosoftOAuthClient("test-client-id", authority, httpClient),
            new XboxLiveClient(xblEndpoint, httpClient),
            new XstsClient(xstsEndpoint, httpClient),
            new MinecraftServicesClient(mcLoginEndpoint, mcProfileEndpoint, httpClient),
            new MicrosoftTokenStore(tempDir),
            // Remplace l'ouverture d'un vrai navigateur : simule exactement ce qu'un navigateur
            // ferait après une connexion Microsoft réussie, en appelant l'URI de redirection.
            authorizeUrl -> {
                String redirectUri = extractRedirectUri(authorizeUrl);
                httpClient.sendAsync(HttpRequest.newBuilder(URI.create(redirectUri + "?code=fake-auth-code&state=x")).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            }
        );

        AuthSession session = provider.authenticate("ignored");

        assertEquals("Notch", session.username());
        assertEquals("069a79f444e94726a5befca90e38aaf5", session.uuid());
        assertEquals("minecraft-access-token", session.accessToken());
        assertEquals("msa", session.userType());
    }

    @Test
    @Timeout(20)
    void validStoredRefreshToken_skipsInteractiveLogin_neverOpensBrowser(@TempDir Path tempDir) throws Exception {
        MicrosoftTokenStore tokenStore = new MicrosoftTokenStore(tempDir);
        tokenStore.store("already-valid-refresh-token");

        HttpServer tokenServer = newServer();
        tokenServer.createContext("/token", exchange -> respond(exchange,
            "{\"access_token\":\"msa-token\",\"refresh_token\":\"rotated-refresh\",\"expires_in\":3600}", 200));
        tokenServer.start();
        String authority = "http://localhost:" + tokenServer.getAddress().getPort();

        HttpServer xblServer = newServer();
        xblServer.createContext("/xbl", exchange -> respond(exchange,
            "{\"Token\":\"xbl-token\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"hash\"}]}}", 200));
        xblServer.start();
        HttpServer xstsServer = newServer();
        xstsServer.createContext("/xsts", exchange -> respond(exchange,
            "{\"Token\":\"xsts-token\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"hash\"}]}}", 200));
        xstsServer.start();
        HttpServer mcServer = newServer();
        mcServer.createContext("/login", exchange -> respond(exchange, "{\"access_token\":\"mc-token\",\"expires_in\":86400}", 200));
        mcServer.createContext("/profile", exchange -> respond(exchange, "{\"id\":\"abc123\",\"name\":\"Steve\"}", 200));
        mcServer.start();

        HttpClient httpClient = HttpClient.newHttpClient();
        AtomicInteger browserOpenCount = new AtomicInteger();
        MicrosoftAuthProvider provider = new MicrosoftAuthProvider(
            new MicrosoftOAuthClient("client-id", authority, httpClient),
            new XboxLiveClient("http://localhost:" + xblServer.getAddress().getPort() + "/xbl", httpClient),
            new XstsClient("http://localhost:" + xstsServer.getAddress().getPort() + "/xsts", httpClient),
            new MinecraftServicesClient(
                "http://localhost:" + mcServer.getAddress().getPort() + "/login",
                "http://localhost:" + mcServer.getAddress().getPort() + "/profile", httpClient),
            tokenStore,
            url -> browserOpenCount.incrementAndGet()
        );

        AuthSession session = provider.authenticate("ignored");

        assertEquals("Steve", session.username());
        assertEquals(0, browserOpenCount.get(), "un refresh token valide ne doit jamais déclencher une nouvelle connexion interactive");
        assertEquals("rotated-refresh", tokenStore.load().orElseThrow(), "le refresh token doit être mis à jour après renouvellement");
    }

    @Test
    @Timeout(20)
    void xstsRejection_propagatesAsAuthenticationFailedException_withTheClearMessage(@TempDir Path tempDir) throws Exception {
        HttpServer tokenServer = newServer();
        tokenServer.createContext("/token", exchange -> respond(exchange,
            "{\"access_token\":\"msa-token\",\"refresh_token\":\"msa-refresh\",\"expires_in\":3600}", 200));
        tokenServer.start();

        HttpServer xblServer = newServer();
        xblServer.createContext("/xbl", exchange -> respond(exchange,
            "{\"Token\":\"xbl-token\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"hash\"}]}}", 200));
        xblServer.start();

        HttpServer xstsServer = newServer();
        xstsServer.createContext("/xsts", exchange -> respond(exchange, "{\"XErr\":2148916233}", 401));
        xstsServer.start();

        HttpClient httpClient = HttpClient.newHttpClient();
        MicrosoftAuthProvider provider = new MicrosoftAuthProvider(
            new MicrosoftOAuthClient("client-id", "http://localhost:" + tokenServer.getAddress().getPort(), httpClient),
            new XboxLiveClient("http://localhost:" + xblServer.getAddress().getPort() + "/xbl", httpClient),
            new XstsClient("http://localhost:" + xstsServer.getAddress().getPort() + "/xsts", httpClient),
            new MinecraftServicesClient("http://unused/login", "http://unused/profile", httpClient),
            new MicrosoftTokenStore(tempDir),
            authorizeUrl -> {
                String redirectUri = extractRedirectUri(authorizeUrl);
                httpClient.sendAsync(HttpRequest.newBuilder(URI.create(redirectUri + "?code=code&state=x")).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            }
        );

        AuthenticationFailedException thrown = assertThrows(AuthenticationFailedException.class,
            () -> provider.authenticate("ignored"));
        assertTrue(thrown.getMessage().contains("xbox.com"), "le message doit rester celui, actionnable, de XstsClient");
    }

    private static String extractRedirectUri(String authorizeUrl) {
        for (String param : URI.create(authorizeUrl).getRawQuery().split("&")) {
            if (param.startsWith("redirect_uri=")) {
                return java.net.URLDecoder.decode(param.substring("redirect_uri=".length()), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("redirect_uri absent de l'URL d'autorisation : " + authorizeUrl);
    }
}
