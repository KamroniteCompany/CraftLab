package com.craftlab.launcher.status;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Le protocole Minecraft (Server List Ping y compris) encode toutes les longueurs/IDs de paquet
 * en VarInt (voir minecraft.wiki/wiki/Java_Edition_protocol/Data_types). Une erreur d'encodage
 * ici casserait silencieusement l'interprétation de CHAQUE paquet, jamais juste un champ isolé —
 * donc testé explicitement, indépendamment de tout réseau.
 */
class ServerStatusCheckerVarIntTest {

    private static int roundTrip(int value) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ServerStatusChecker.writeVarInt(new DataOutputStream(buffer), value);
        return ServerStatusChecker.readVarInt(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));
    }

    @Test
    void zero_roundTrips() throws Exception {
        assertEquals(0, roundTrip(0));
    }

    @Test
    void singleByteValue_roundTrips() throws Exception {
        assertEquals(127, roundTrip(127));
    }

    @Test
    void multiByteValue_roundTrips() throws Exception {
        // 128 est la plus petite valeur qui déborde sur un deuxième octet (voir l'algorithme).
        assertEquals(128, roundTrip(128));
        assertEquals(300, roundTrip(300));
    }

    @Test
    void largeValue_roundTrips() throws Exception {
        assertEquals(2_097_151, roundTrip(2_097_151)); // 3 octets pleins
        assertEquals(Integer.MAX_VALUE, roundTrip(Integer.MAX_VALUE));
    }

    @Test
    void negativeOne_roundTrips() throws Exception {
        // Utilisé tel quel comme "protocol version" dans le handshake (voir writeHandshake).
        assertEquals(-1, roundTrip(-1));
    }
}
