package com.communityserver.communitytest.github;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Lit un éventuel token GitHub depuis config/communitytest/config.properties (clé github_token),
 * le même fichier que vote_duration_seconds (VoteConfig). Jamais hardcodé, jamais obligatoire :
 * sans token, GitHubClient utilise les limites publiques de l'API GitHub (60 req/h), largement
 * suffisantes pour un import déclenché manuellement par un administrateur.
 */
public final class GitHubConfig {

    private static final String FOLDER_NAME = "communitytest";
    private static final String FILE_NAME = "config.properties";

    private static volatile String token = null;

    private GitHubConfig() {
    }

    public static String getToken() {
        return token;
    }

    public static void load() {
        Path configFile = FMLPaths.CONFIGDIR.get().resolve(FOLDER_NAME).resolve(FILE_NAME);
        Properties properties = new Properties();

        if (Files.exists(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                properties.load(in);
            } catch (IOException e) {
                token = null;
                return;
            }
        }

        if (!properties.containsKey("github_token")) {
            properties.setProperty("github_token", "");
            writeBack(configFile, properties);
        }

        String value = properties.getProperty("github_token");
        token = (value != null && !value.isBlank()) ? value.trim() : null;
    }

    private static void writeBack(Path configFile, Properties properties) {
        try {
            Files.createDirectories(configFile.getParent());
            try (var out = Files.newOutputStream(configFile)) {
                properties.store(out, "Config CommunityTest. github_token est optionnel : laissez vide pour "
                    + "utiliser les limites publiques de l'API GitHub (60 requetes/heure).");
            }
        } catch (IOException ignored) {
            // Pas bloquant : le token restera simplement absent en memoire pour cette session.
        }
    }
}
