package com.craftlab.launcher.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Non-régression explicite (voir la mission "authentification Microsoft") : l'ajout de
 * MicrosoftAuthProvider ne doit rien changer au fonctionnement d'OfflineAuthProvider, toujours
 * utilisé par défaut dans CraftLabLauncherApp.
 */
class OfflineAuthProviderTest {

    @Test
    void sameUsername_alwaysProducesTheSameUuid() {
        OfflineAuthProvider provider = new OfflineAuthProvider();

        AuthSession first = provider.authenticate("Steve");
        AuthSession second = provider.authenticate("Steve");

        assertEquals(first.uuid(), second.uuid());
    }

    @Test
    void uuid_matchesVanillaOfflineAlgorithm_withoutDashes() {
        OfflineAuthProvider provider = new OfflineAuthProvider();
        String expected = UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes(StandardCharsets.UTF_8))
            .toString().replace("-", "");

        AuthSession session = provider.authenticate("Steve");

        assertEquals(expected, session.uuid());
        assertFalse(session.uuid().contains("-"), "auth_uuid doit rester sans tirets, voir MinecraftLauncher");
    }

    @Test
    void differentUsernames_produceDifferentUuids() {
        OfflineAuthProvider provider = new OfflineAuthProvider();

        assertFalse(provider.authenticate("Steve").uuid().equals(provider.authenticate("Alex").uuid()));
    }

    @Test
    void usesLegacyUserType_neverMsa() {
        OfflineAuthProvider provider = new OfflineAuthProvider();

        assertEquals("legacy", provider.authenticate("Steve").userType());
    }

    @Test
    void accessToken_isNeverARealToken() {
        OfflineAuthProvider provider = new OfflineAuthProvider();

        assertEquals("0", provider.authenticate("Steve").accessToken());
    }
}
