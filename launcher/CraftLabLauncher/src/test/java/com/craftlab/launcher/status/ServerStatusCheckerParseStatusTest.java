package com.craftlab.launcher.status;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** parseStatus() sur des formes réelles/dégradées de réponse Status Response. */
class ServerStatusCheckerParseStatusTest {

    @Test
    void normalResponse_extractsOnlineAndMaxPlayers() {
        String json = "{\"version\":{\"name\":\"1.21.1\",\"protocol\":767},"
            + "\"players\":{\"max\":20,\"online\":3},"
            + "\"description\":{\"text\":\"Serveur CraftLab\"}}";

        ServerStatus status = ServerStatusChecker.parseStatus(json);

        assertTrue(status.online());
        assertEquals(3, status.onlinePlayers());
        assertEquals(20, status.maxPlayers());
    }

    @Test
    void missingPlayersField_stillReportsOnline_withNullCounts() {
        // Un serveur peut techniquement omettre "players" ; ne jamais interpréter ça comme 0 joueur.
        String json = "{\"version\":{\"name\":\"1.21.1\",\"protocol\":767},"
            + "\"description\":{\"text\":\"Serveur CraftLab\"}}";

        ServerStatus status = ServerStatusChecker.parseStatus(json);

        assertTrue(status.online());
        assertNull(status.onlinePlayers());
        assertNull(status.maxPlayers());
    }

    @Test
    void zeroPlayersOnline_isDistinctFromMissingField() {
        String json = "{\"players\":{\"max\":20,\"online\":0}}";

        ServerStatus status = ServerStatusChecker.parseStatus(json);

        assertEquals(0, status.onlinePlayers());
    }

    @Test
    void malformedJson_throws_forCheckBlockingToTurnIntoOffline() {
        // checkBlocking() attrape cette exception et la transforme en ServerStatus.offline(...) ;
        // parseStatus() lui-même doit rester strict pour ne jamais masquer une réponse invalide.
        assertThrows(RuntimeException.class, () -> ServerStatusChecker.parseStatus("{ not json"));
    }
}
