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

/**
 * Les codes XErr couvrent les cas réels où le COMPTE (pas le launcher) empêche de jouer — pas de
 * profil Xbox, restriction régionale, compte enfant sans accord parental. Chacun doit produire
 * un message directement compréhensible par le joueur, pas juste "erreur 401".
 */
class XstsClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(String body, int status) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/authorize", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort() + "/authorize";
    }

    @Test
    void authorize_success() throws Exception {
        String endpoint = startServer(
            "{\"Token\":\"xsts-token-value\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"user-hash-value\"}]}}", 200);
        XstsClient client = new XstsClient(endpoint, HttpClient.newHttpClient());

        XboxLiveToken token = client.authorize("xbl-token");

        assertEquals("xsts-token-value", token.token());
        assertEquals("user-hash-value", token.userHash());
    }

    @Test
    void noXboxAccount_producesAClearActionableMessage() throws Exception {
        String endpoint = startServer("{\"XErr\":2148916233}", 401);
        XstsClient client = new XstsClient(endpoint, HttpClient.newHttpClient());

        MicrosoftAuthException thrown = assertThrows(MicrosoftAuthException.class, () -> client.authorize("xbl-token"));
        assertTrue(thrown.getMessage().contains("xbox.com"));
    }

    @Test
    void childAccountWithoutFamily_producesAClearActionableMessage() throws Exception {
        String endpoint = startServer("{\"XErr\":2148916238}", 401);
        XstsClient client = new XstsClient(endpoint, HttpClient.newHttpClient());

        MicrosoftAuthException thrown = assertThrows(MicrosoftAuthException.class, () -> client.authorize("xbl-token"));
        assertTrue(thrown.getMessage().toLowerCase().contains("enfant") || thrown.getMessage().toLowerCase().contains("famille"));
    }

    @Test
    void unknownXErrCode_stillProducesAMessageContainingTheCode() throws Exception {
        String endpoint = startServer("{\"XErr\":9999999999}", 401);
        XstsClient client = new XstsClient(endpoint, HttpClient.newHttpClient());

        MicrosoftAuthException thrown = assertThrows(MicrosoftAuthException.class, () -> client.authorize("xbl-token"));
        assertTrue(thrown.getMessage().contains("9999999999"));
    }

    @Test
    void non200WithoutXErr_throwsGenericCleanMessage() throws Exception {
        String endpoint = startServer("{}", 500);
        XstsClient client = new XstsClient(endpoint, HttpClient.newHttpClient());

        assertThrows(MicrosoftAuthException.class, () -> client.authorize("xbl-token"));
    }
}
