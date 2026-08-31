package com.craftlab.launcher.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Couvre la séparation entre les valeurs PAR DÉFAUT (embarquées, voir
 * default-launcher.properties) et les valeurs UTILISATEUR (fichier local) : un joueur qui n'a
 * jamais rien modifié doit obtenir les défauts embarqués, et une seule clé modifiée par
 * l'utilisateur ne doit jamais faire perdre les autres défauts.
 */
class LauncherConfigTest {

    @Test
    void freshInstall_usesBundledDefaults(@TempDir Path tempDir) {
        LauncherConfig config = new LauncherConfig(tempDir);

        config.load();

        assertEquals("localhost", config.getServerAddress());
        assertEquals(25565, config.getServerPort());
        assertEquals("Player", config.getUsername());
        assertEquals(1280, config.getResolutionWidth());
        assertEquals(720, config.getResolutionHeight());
    }

    @Test
    void freshInstall_writesAFileWithAllKeys_forTheUserToEdit(@TempDir Path tempDir) throws Exception {
        LauncherConfig config = new LauncherConfig(tempDir);

        config.load();

        Path file = tempDir.resolve("launcher.properties");
        assertTrue(Files.exists(file), "un fichier utilisateur éditable doit être créé au premier lancement");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(content.contains("server_address=localhost"));
    }

    @Test
    void userFileOverridesOnlyTheKeysItSets_othersStayAtDefault(@TempDir Path tempDir) throws Exception {
        // L'utilisateur n'a modifié que server_address (cas réel : pointer vers le vrai serveur
        // CraftLab) — server_port, username, résolution doivent rester aux valeurs par défaut.
        Files.writeString(tempDir.resolve("launcher.properties"), "server_address=play.craftlab.example\n",
            StandardCharsets.UTF_8);

        LauncherConfig config = new LauncherConfig(tempDir);
        config.load();

        assertEquals("play.craftlab.example", config.getServerAddress());
        assertEquals(25565, config.getServerPort(), "server_port non modifié par l'utilisateur doit rester au défaut");
        assertEquals("Player", config.getUsername(), "username non modifié par l'utilisateur doit rester au défaut");
    }

    @Test
    void userValues_survivePastAReload(@TempDir Path tempDir) {
        LauncherConfig first = new LauncherConfig(tempDir);
        first.load();
        first.setUsername("Alice");
        first.setServerAddress("play.craftlab.example");
        first.save();

        LauncherConfig reloaded = new LauncherConfig(tempDir);
        reloaded.load();

        assertEquals("Alice", reloaded.getUsername());
        assertEquals("play.craftlab.example", reloaded.getServerAddress());
    }
}
