package com.craftlab.craftlabcore.github;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Lit config/craftlabcore/config.properties (même fichier que GitHubConfig/VoteConfig) pour
 * la clé github_refresh_interval_minutes : à quelle fréquence GitHubRefreshScheduler relance
 * automatiquement /mod github refresh. 0 désactive complètement le scheduler (la commande
 * manuelle /mod github refresh reste disponible dans tous les cas).
 */
public final class GitHubRefreshConfig {

    private static final String FOLDER_NAME = "craftlabcore";
    private static final String FILE_NAME = "config.properties";
    private static final long DEFAULT_INTERVAL_MINUTES = 60L;

    private static volatile long intervalMinutes = DEFAULT_INTERVAL_MINUTES;

    private GitHubRefreshConfig() {
    }

    public static long getIntervalMinutes() {
        return intervalMinutes;
    }

    public static void load() {
        Path configFile = FMLPaths.CONFIGDIR.get().resolve(FOLDER_NAME).resolve(FILE_NAME);
        Properties properties = new Properties();

        if (Files.exists(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                properties.load(in);
            } catch (IOException e) {
                intervalMinutes = DEFAULT_INTERVAL_MINUTES;
                return;
            }
        }

        if (!properties.containsKey("github_refresh_interval_minutes")) {
            properties.setProperty("github_refresh_interval_minutes", String.valueOf(DEFAULT_INTERVAL_MINUTES));
            writeBack(configFile, properties);
        }

        intervalMinutes = parseLong(properties.getProperty("github_refresh_interval_minutes"), DEFAULT_INTERVAL_MINUTES);
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

    private static void writeBack(Path configFile, Properties properties) {
        try {
            Files.createDirectories(configFile.getParent());
            try (OutputStream out = Files.newOutputStream(configFile)) {
                properties.store(out, "Config CraftLabCore. github_refresh_interval_minutes : frequence (en minutes) "
                    + "de la verification automatique des releases GitHub pour les mods ACCEPTED deja rattaches "
                    + "(equivalent a relancer /mod github refresh). 0 desactive le scheduler automatique ; "
                    + "la commande manuelle reste toujours disponible. Ne touche jamais CURRENT.");
            }
        } catch (IOException ignored) {
            // Pas bloquant : la valeur par defaut reste utilisee en memoire pour cette session.
        }
    }
}
