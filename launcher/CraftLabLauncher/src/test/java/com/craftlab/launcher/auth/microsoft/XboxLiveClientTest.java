package com.craftlab.launcher.auth.microsoft;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class XboxLiveClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(String body, int status) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/authenticate", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort() + "/authenticate";
    }

    @Test
    void authenticate_extractsTokenAndUserHash() throws Exception {
        String endpoint = startServer(
            "{\"Token\":\"xbl-token-value\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"user-hash-value\"}]}}", 200);
        XboxLiveClient client = new XboxLiveClient(endpoint, HttpClient.newHttpClient());

        XboxLiveToken token = client.authenticate("microsoft-access-token");

        assertEquals("xbl-token-value", token.token());
        assertEquals("user-hash-value", token.userHash());
    }

    @Test
    void non200Response_throwsCleanly() throws Exception {
        String endpoint = startServer("{}", 401);
        XboxLiveClient client = new XboxLiveClient(endpoint, HttpClient.newHttpClient());

        assertThrows(MicrosoftAuthException.class, () -> client.authenticate("token"));
    }

    @Test
    void malformedResponse_throwsCleanly() throws Exception {
        String endpoint = startServer("{\"unexpected\":\"shape\"}", 200);
        XboxLiveClient client = new XboxLiveClient(endpoint, HttpClient.newHttpClient());

        assertThrows(MicrosoftAuthException.class, () -> client.authenticate("token"));
    }

    @Test
    void unreachableEndpoint_throwsCleanly() {
        XboxLiveClient client = new XboxLiveClient("http://localhost:1/authenticate", HttpClient.newHttpClient());

        assertThrows(MicrosoftAuthException.class, () -> client.authenticate("token"));
    }
}
