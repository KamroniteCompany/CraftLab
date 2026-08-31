package com.craftlab.launcher.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Couvre checkBlocking() de bout en bout contre un vrai socket TCP local qui répond exactement
 * le protocole Server List Ping (handshake reçu, requête de statut reçue, réponse JSON envoyée
 * avec le même framing VarInt) — sans dépendre d'un vrai serveur Minecraft, tout en exerçant le
 * vrai code réseau (pas une simulation au niveau Java uniquement).
 */
class ServerStatusCheckerIntegrationTest {

    @Test
    @Timeout(10)
    void realSocket_onlineServer_reportsOnlineWithPlayerCounts() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            String statusJson = "{\"version\":{\"name\":\"1.21.1\",\"protocol\":767},"
                + "\"players\":{\"max\":20,\"online\":5},\"description\":{\"text\":\"Test\"}}";

            Thread serverThread = new Thread(() -> serveOneStatusRequest(server, statusJson));
            serverThread.setDaemon(true);
            serverThread.start();

            ServerStatus status = ServerStatusChecker.checkBlocking("localhost", port);

            assertTrue(status.online(), "detail=" + status.detail());
            assertEquals(5, status.onlinePlayers());
            assertEquals(20, status.maxPlayers());
        }
    }

    @Test
    @Timeout(10)
    void nothingListening_reportsOfflineQuickly() {
        // Port fermé (rien n'écoute jamais dessus) : simule un serveur réellement éteint.
        long start = System.nanoTime();

        ServerStatus status = ServerStatusChecker.checkBlocking("localhost", 1);

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertFalse(status.online());
        assertNotNull(status.detail());
        assertTrue(elapsedMs < 5000, "doit échouer rapidement (timeout court), a pris " + elapsedMs + " ms");
    }

    @Test
    @Timeout(10)
    void connectionAcceptedButNoResponse_timesOutAndReportsOffline() throws Exception {
        // Le serveur accepte la connexion mais ne répond jamais : couvre le cas où le port est
        // ouvert (ex. un autre service y écoute par erreur) sans qu'aucune donnée n'arrive jamais,
        // distinct du cas "connexion refusée" ci-dessus.
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket ignored = server.accept()) {
                    Thread.sleep(10_000);
                } catch (Exception ignored) {
                    // Le test se termine avant : sans conséquence.
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            long start = System.nanoTime();
            ServerStatus status = ServerStatusChecker.checkBlocking("localhost", port);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertFalse(status.online());
            assertTrue(elapsedMs < 5000, "le timeout de lecture doit borner l'attente, a pris " + elapsedMs + " ms");
        }
    }

    /** Implémente juste assez du protocole SLP côté serveur pour répondre une seule requête de statut. */
    private static void serveOneStatusRequest(ServerSocket server, String statusJson) {
        try (Socket client = server.accept()) {
            DataInputStream in = new DataInputStream(client.getInputStream());
            DataOutputStream out = new DataOutputStream(client.getOutputStream());

            readPacket(in); // Handshake — contenu non revalidé ici, seul le framing compte pour ce test
            readPacket(in); // Status Request

            byte[] jsonBytes = statusJson.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            DataOutputStream bodyOut = new DataOutputStream(body);
            ServerStatusChecker.writeVarInt(bodyOut, 0x00); // packet ID : Status Response
            ServerStatusChecker.writeVarInt(bodyOut, jsonBytes.length);
            bodyOut.write(jsonBytes);

            ServerStatusChecker.writeVarInt(out, body.size());
            out.write(body.toByteArray());
            out.flush();
        } catch (Exception ignored) {
            // Le test échouera de toute façon sur l'assertion côté client si quelque chose casse ici.
        }
    }

    private static byte[] readPacket(DataInputStream in) throws Exception {
        int length = ServerStatusChecker.readVarInt(in);
        return in.readNBytes(length);
    }
}
