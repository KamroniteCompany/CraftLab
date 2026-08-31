package com.craftlab.launcher.status;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Interroge un serveur Minecraft via Server List Ping (SLP) — le même protocole que l'écran
 * "Multijoueur" vanilla utilise pour afficher le statut d'un serveur dans la liste, disponible
 * sur le port de jeu normal (25565 par défaut), sans configuration serveur supplémentaire et
 * totalement indépendant de RCON. Compatible avec toute version moderne du protocole (le champ
 * "protocol version" envoyé dans le handshake n'a pas besoin d'être exact pour une requête de
 * statut — voir handshake() ci-dessous), donc valable pour Minecraft 1.21.1 + Forge 52.1.0 sans
 * suivre les changements de version de protocole.
 *
 * Jamais bloquant pour l'appelant : check() délègue immédiatement à un exécuteur dédié
 * (threads démons, jamais responsables de garder la JVM ouverte) et applique un timeout court à
 * la fois à la connexion et à la lecture — un serveur injoignable ou qui ne répond pas est
 * rapporté comme "offline" en quelques secondes au maximum, jamais indéfiniment.
 */
public final class ServerStatusChecker {

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 3000;
    private static final int MAX_PACKET_LENGTH = 1 << 20; // 1 Mio — se protège d'une réponse malformée/hostile

    private final ExecutorService executor;

    public ServerStatusChecker() {
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "CraftLab-ServerStatus");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Ne lève jamais d'exception : toute erreur réseau devient un ServerStatus.offline(...). */
    public CompletableFuture<ServerStatus> check(String host, int port) {
        return CompletableFuture.supplyAsync(() -> checkBlocking(host, port), executor);
    }

    /** Threads déjà démons (n'empêchent jamais la JVM de se fermer) : purement cosmétique. */
    public void shutdown() {
        executor.shutdownNow();
    }

    /** Partie synchrone, séparée de check() pour être testable sans passer par l'exécuteur async. */
    static ServerStatus checkBlocking(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            writeHandshake(out, host, port);
            writeStatusRequest(out);

            String json = readStatusResponseJson(in);
            return parseStatus(json);
        } catch (SocketTimeoutException e) {
            return ServerStatus.offline("Serveur injoignable (délai dépassé après " + READ_TIMEOUT_MS + " ms).");
        } catch (IOException e) {
            return ServerStatus.offline("Serveur injoignable : " + e.getMessage());
        } catch (RuntimeException e) {
            // Réponse reçue mais illisible (JSON invalide, champ inattendu, etc.) : ne jamais faire
            // planter l'appelant pour une réponse malformée, la traiter comme une indisponibilité.
            return ServerStatus.offline("Réponse du serveur illisible : " + e.getMessage());
        }
    }

    private static void writeHandshake(DataOutputStream out, String host, int port) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(body);
        writeVarInt(data, 0x00); // packet ID : Handshake
        // Version de protocole : -1 signale "peu importe, je veux juste le statut" — accepté par
        // toute implémentation Minecraft moderne pour une requête de statut (jamais validé
        // strictement en dehors d'une tentative de connexion réelle).
        writeVarInt(data, -1);
        writeString(data, host);
        data.writeShort(port);
        writeVarInt(data, 1); // next state : 1 = Status
        writePacket(out, body.toByteArray());
    }

    private static void writeStatusRequest(DataOutputStream out) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(body);
        writeVarInt(data, 0x00); // packet ID : Status Request, corps vide
        writePacket(out, body.toByteArray());
    }

    private static void writePacket(DataOutputStream out, byte[] body) throws IOException {
        writeVarInt(out, body.length);
        out.write(body);
        out.flush();
    }

    private static String readStatusResponseJson(DataInputStream in) throws IOException {
        int packetLength = readVarInt(in);
        if (packetLength < 0 || packetLength > MAX_PACKET_LENGTH) {
            throw new IOException("Longueur de paquet invalide : " + packetLength);
        }
        byte[] packet = in.readNBytes(packetLength);
        if (packet.length != packetLength) {
            throw new IOException("Paquet tronqué : attendu " + packetLength + " octets, reçu " + packet.length);
        }

        DataInputStream packetStream = new DataInputStream(new java.io.ByteArrayInputStream(packet));
        int packetId = readVarInt(packetStream);
        if (packetId != 0x00) {
            throw new IOException("ID de paquet inattendu : " + packetId + " (0x00 attendu pour Status Response)");
        }
        return readString(packetStream);
    }

    static ServerStatus parseStatus(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Integer online = null;
        Integer max = null;
        if (root.has("players") && root.get("players").isJsonObject()) {
            JsonObject players = root.getAsJsonObject("players");
            if (players.has("online")) {
                online = players.get("online").getAsInt();
            }
            if (players.has("max")) {
                max = players.get("max").getAsInt();
            }
        }
        return ServerStatus.online(online, max);
    }

    // ---- VarInt / String : encodage du protocole Minecraft (voir wiki.vg / minecraft.wiki, "Protocol") ----

    static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.writeByte(value);
                return;
            }
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    static int readVarInt(DataInputStream in) throws IOException {
        int result = 0;
        int position = 0;
        while (true) {
            byte current = in.readByte();
            result |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return result;
            }
            position += 7;
            if (position >= 32) {
                throw new IOException("VarInt trop long (paquet malformé).");
            }
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        if (length < 0 || length > MAX_PACKET_LENGTH) {
            throw new IOException("Longueur de chaîne invalide : " + length);
        }
        byte[] bytes = in.readNBytes(length);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
