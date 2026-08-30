package com.craftlab.craftlabcore.download;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Lit max_mod_download_size_mb et max_concurrent_mod_downloads depuis
 * config/craftlabcore/config.properties (même fichier que vote_duration_seconds et
 * github_token). Lue une seule fois au démarrage du serveur (ModDownloadManager fige ces
 * valeurs à sa construction) : contrairement à la durée d'un vote, changer ces limites
 * nécessite donc un redémarrage du serveur.
 */
public final class DownloadConfig {

    private static final String FOLDER_NAME = "craftlabcore";
    private static final String FILE_NAME = "config.properties";
    private static final long DEFAULT_MAX_SIZE_MB = 200L;
    private static final int DEFAULT_MAX_CONCURRENT = 2;

    private static volatile long maxSizeBytes = DEFAULT_MAX_SIZE_MB * 1024 * 1024;
    private static volatile int maxConcurrentDownloads = DEFAULT_MAX_CONCURRENT;

    private DownloadConfig() {
    }

    public static long getMaxDownloadSizeBytes() {
        return maxSizeBytes;
    }

    public static int getMaxConcurrentDownloads() {
        return maxConcurrentDownloads;
    }

    public static void load() {
        Path configFile = FMLPaths.CONFIGDIR.get().resolve(FOLDER_NAME).resolve(FILE_NAME);
        Properties properties = new Properties();

        if (Files.exists(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                properties.load(in);
            } catch (IOException e) {
                maxSizeBytes = DEFAULT_MAX_SIZE_MB * 1024 * 1024;
                maxConcurrentDownloads = DEFAULT_MAX_CONCURRENT;
                return;
            }
        }

        boolean changed = false;
        if (!properties.containsKey("max_mod_download_size_mb")) {
            properties.setProperty("max_mod_download_size_mb", String.valueOf(DEFAULT_MAX_SIZE_MB));
            changed = true;
        }
        if (!properties.containsKey("max_concurrent_mod_downloads")) {
            properties.setProperty("max_concurrent_mod_downloads", String.valueOf(DEFAULT_MAX_CONCURRENT));
            changed = true;
        }
        if (changed) {
            writeBack(configFile, properties);
        }

        long sizeMb = parseLong(properties.getProperty("max_mod_download_size_mb"), DEFAULT_MAX_SIZE_MB);
        maxSizeBytes = Math.max(1L, sizeMb) * 1024 * 1024;
        maxConcurrentDownloads = Math.max(1, (int) parseLong(properties.getProperty("max_concurrent_mod_downloads"), DEFAULT_MAX_CONCURRENT));
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
            try (var out = Files.newOutputStream(configFile)) {
                properties.store(out, "Config CraftLabCore.");
            }
        } catch (IOException ignored) {
            // Pas bloquant : les valeurs par defaut restent utilisees en memoire pour cette session.
        }
    }
}
