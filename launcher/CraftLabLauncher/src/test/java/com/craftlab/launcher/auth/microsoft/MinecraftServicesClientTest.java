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

class MinecraftServicesClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void loginWithXbox_success() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/login", exchange -> {
            byte[] bytes = "{\"access_token\":\"mc-access-token\",\"expires_in\":86400}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String endpoint = "http://localhost:" + server.getAddress().getPort() + "/login";
        MinecraftServicesClient client = new MinecraftServicesClient(endpoint, "http://unused", HttpClient.newHttpClient());

        MinecraftAuthResult result = client.loginWithXbox("user-hash", "xsts-token");

        assertEquals("mc-access-token", result.accessToken());
        assertEquals(86400L, result.expiresInSeconds());
    }

    @Test
    void fetchProfile_success() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/profile", exchange -> {
            byte[] bytes = "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String endpoint = "http://localhost:" + server.getAddress().getPort() + "/profile";
        MinecraftServicesClient client = new MinecraftServicesClient("http://unused", endpoint, HttpClient.newHttpClient());

        MinecraftProfile profile = client.fetchProfile("mc-access-token");

        assertEquals("069a79f444e94726a5befca90e38aaf5", profile.id());
        assertEquals("Notch", profile.name());
    }

    @Test
    void fetchProfile_404_meansGameNotOwned() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/profile", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        String endpoint = "http://localhost:" + server.getAddress().getPort() + "/profile";
        MinecraftServicesClient client = new MinecraftServicesClient("http://unused", endpoint, HttpClient.newHttpClient());

        MicrosoftAuthException thrown = assertThrows(MicrosoftAuthException.class, () -> client.fetchProfile("token"));
        assertTrue(thrown.getMessage().contains("minecraft.net"));
    }

    @Test
    void loginWithXbox_serverError_throwsCleanly() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/login", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        String endpoint = "http://localhost:" + server.getAddress().getPort() + "/login";
        MinecraftServicesClient client = new MinecraftServicesClient(endpoint, "http://unused", HttpClient.newHttpClient());

        assertThrows(MicrosoftAuthException.class, () -> client.loginWithXbox("hash", "token"));
    }
}
