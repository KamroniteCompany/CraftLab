package com.craftlab.launcher.modpack;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Couvre le comportement de HttpModPackProvider quand le serveur qui héberge le ModPack (le
 * serveur CraftLab réel, ou GitHub Pages/raw, selon l'hébergement choisi) répond correctement,
 * répond avec une erreur, ou est totalement injoignable — sans jamais rester bloqué
 * indéfiniment (voir SyncManager.sync(), qui dépend de ce contrat pour ne jamais geler
 * l'interface au démarrage du launcher).
 */
class HttpModPackProviderTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(String body, int statusCode) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/current-modpack-launcher.json", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort() + "/current-modpack-launcher.json";
    }

    @Test
    void successfulResponse_parsesTheRemoteModPack() throws Exception {
        String json = """
            {
              "minecraftVersion": "1.21.1",
              "forgeVersion": "52.1.0",
              "generation": 3,
              "mods": [
                {"modId": "blankmod", "name": "BlankMod", "version": "1.0.0",
                 "assetName": "blankmod-1.0.0.jar", "sha256": "abc", "size": 2029,
                 "downloadUrl": "https://example.invalid/blankmod-1.0.0.jar"}
              ]
            }""";
        String url = startServer(json, 200);
        HttpModPackProvider provider = new HttpModPackProvider(url);

        RemoteModPack remote = provider.getCurrentModPack().get(10, TimeUnit.SECONDS);

        assertEquals("1.21.1", remote.minecraftVersion());
        assertEquals(3, remote.generation());
        assertEquals(1, remote.mods().size());
        assertEquals("blankmod", remote.mods().get(0).modId());
    }

    @Test
    void non200Response_completesExceptionallyWithModPackFetchException() throws Exception {
        String url = startServer("Internal Server Error", 500);
        HttpModPackProvider provider = new HttpModPackProvider(url);

        ExecutionException thrown = assertThrows(ExecutionException.class,
            () -> provider.getCurrentModPack().get(10, TimeUnit.SECONDS));
        assertTrue(thrown.getCause() instanceof ModPackFetchException);
        assertTrue(thrown.getCause().getMessage().contains("500"));
    }

    @Test
    void unreachableServer_failsQuicklyInsteadOfHangingForever() throws Exception {
        // Port fermé (rien n'écoute jamais dessus) : simule un serveur CraftLab/GitHub indisponible.
        HttpModPackProvider provider = new HttpModPackProvider("http://localhost:1/current-modpack-launcher.json");

        CompletableFuture<RemoteModPack> future = provider.getCurrentModPack();

        ExecutionException thrown = assertThrows(ExecutionException.class,
            () -> future.get(15, TimeUnit.SECONDS), "ne doit jamais bloquer indéfiniment quand le serveur est injoignable");
        assertTrue(thrown.getCause() instanceof ModPackFetchException);
    }

    @Test
    void malformedJson_completesExceptionallyInsteadOfThrowingUncaught() throws Exception {
        String url = startServer("{ not valid json at all", 200);
        HttpModPackProvider provider = new HttpModPackProvider(url);

        ExecutionException thrown = assertThrows(ExecutionException.class,
            () -> provider.getCurrentModPack().get(10, TimeUnit.SECONDS));
        assertTrue(thrown.getCause() instanceof ModPackFetchException);
    }
}
