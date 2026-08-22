package com.communityserver.communitytest.vote;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Lit config/communitytest/config.properties (créé avec une valeur par défaut s'il n'existe pas).
 * VoteManager relit cette config à chaque /communitytest start, donc changer la valeur
 * puis relancer un vote suffit pour tester : pas besoin de redémarrer le serveur.
 */
public final class VoteConfig {

    private static final String FOLDER_NAME = "communitytest";
    private static final String FILE_NAME = "config.properties";
    private static final long DEFAULT_DURATION_SECONDS = 604800L; // 7 jours

    private static volatile long voteDurationSeconds = DEFAULT_DURATION_SECONDS;

    private VoteConfig() {
    }

    public static long getVoteDurationSeconds() {
        return voteDurationSeconds;
    }

    public static void load() {
        Path configFile = FMLPaths.CONFIGDIR.get().resolve(FOLDER_NAME).resolve(FILE_NAME);

        if (!Files.exists(configFile)) {
            voteDurationSeconds = DEFAULT_DURATION_SECONDS;
            writeDefault(configFile);
            return;
        }

        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(configFile)) {
            properties.load(in);
            voteDurationSeconds = parseLong(properties.getProperty("vote_duration_seconds"), DEFAULT_DURATION_SECONDS);
        } catch (IOException e) {
            voteDurationSeconds = DEFAULT_DURATION_SECONDS;
        }
    }

    private static long parseLong(String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void writeDefault(Path configFile) {
        Properties properties = new Properties();
        properties.setProperty("vote_duration_seconds", String.valueOf(DEFAULT_DURATION_SECONDS));
        try {
            Files.createDirectories(configFile.getParent());
            try (OutputStream out = Files.newOutputStream(configFile)) {
                properties.store(out, "Duree d'un vote CommunityTest, en secondes. 604800 = 7 jours. Ex : 60 pour un vote de test d'une minute.");
            }
        } catch (IOException ignored) {
            // Pas bloquant : la valeur par defaut reste utilisee en memoire.
        }
    }
}
