package com.craftlab.launcher.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuration locale : source du ModPack, adresse du serveur CraftLab, pseudo. Pour cette
 * première version, un seul profil ; rien n'empêche d'étendre vers plusieurs profils plus tard.
 *
 * Deux couches, jamais mélangées dans le code : les valeurs PAR DÉFAUT (embarquées dans le JAR,
 * voir default-launcher.properties dans les ressources — le seul fichier à modifier pour changer
 * ce qu'obtient un joueur au tout premier lancement) et les valeurs UTILISATEUR (le fichier
 * modifiable dans %APPDATA%\CraftLabLauncher\, qui l'emporte clé par clé sur les défauts dès
 * qu'elle existe). Aucun chemin ni adresse propre à une machine de développement n'est codé en
 * dur dans cette classe.
 */
public final class LauncherConfig {

    private static final String FILE_NAME = "launcher.properties";
    private static final String DEFAULTS_RESOURCE = "/default-launcher.properties";

    private final Path filePath;
    private String modPackUrl;
    private String serverAddress;
    private int serverPort;
    private String username;
    private int resolutionWidth;
    private int resolutionHeight;

    public LauncherConfig(Path craftLabRoot) {
        this.filePath = craftLabRoot.resolve(FILE_NAME);
    }

    public void load() {
        Properties defaults = loadBundledDefaults();
        Properties properties = new Properties(defaults);
        if (Files.exists(filePath)) {
            try (InputStream in = Files.newInputStream(filePath)) {
                properties.load(in);
            } catch (IOException ignored) {
                // On repart des valeurs par défaut ci-dessus.
            }
        }

        modPackUrl = properties.getProperty("modpack_url");
        serverAddress = properties.getProperty("server_address");
        serverPort = parseInt(properties.getProperty("server_port"), 25565);
        username = properties.getProperty("username");
        resolutionWidth = parseInt(properties.getProperty("resolution_width"), 1280);
        resolutionHeight = parseInt(properties.getProperty("resolution_height"), 720);

        save(); // garantit que le fichier existe avec toutes les clés, modifiable à la main
    }

    /**
     * Ultime filet de sécurité si default-launcher.properties venait à manquer du JAR (ne devrait
     * jamais arriver en pratique) : mieux vaut des valeurs de secours en dur ici, dans un seul
     * endroit clairement identifié, que null propagé dans toute l'application.
     */
    private static Properties loadBundledDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = LauncherConfig.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in != null) {
                defaults.load(in);
                return defaults;
            }
        } catch (IOException ignored) {
            // Filet de sécurité ci-dessous.
        }
        defaults.setProperty("modpack_url", "file:./current-modpack-launcher.json");
        defaults.setProperty("server_address", "localhost");
        defaults.setProperty("server_port", "25565");
        defaults.setProperty("username", "Player");
        defaults.setProperty("resolution_width", "1280");
        defaults.setProperty("resolution_height", "720");
        return defaults;
    }

    public void save() {
        Properties properties = new Properties();
        properties.setProperty("modpack_url", modPackUrl);
        properties.setProperty("server_address", serverAddress);
        properties.setProperty("server_port", String.valueOf(serverPort));
        properties.setProperty("username", username);
        properties.setProperty("resolution_width", String.valueOf(resolutionWidth));
        properties.setProperty("resolution_height", String.valueOf(resolutionHeight));

        try {
            Files.createDirectories(filePath.getParent());
            try (OutputStream out = Files.newOutputStream(filePath)) {
                properties.store(out, "Configuration du CraftLab Launcher.");
            }
        } catch (IOException ignored) {
            // Pas bloquant : les valeurs restent utilisées en mémoire pour cette session.
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public String getModPackUrl() {
        return modPackUrl;
    }

    public void setModPackUrl(String modPackUrl) {
        this.modPackUrl = modPackUrl;
    }

    public String getServerAddress() {
        return serverAddress;
    }

    public void setServerAddress(String serverAddress) {
        this.serverAddress = serverAddress;
    }

    public int getServerPort() {
        return serverPort;
    }

    public void setServerPort(int serverPort) {
        this.serverPort = serverPort;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public int getResolutionWidth() {
        return resolutionWidth;
    }

    public void setResolutionWidth(int resolutionWidth) {
        this.resolutionWidth = resolutionWidth;
    }

    public int getResolutionHeight() {
        return resolutionHeight;
    }

    public void setResolutionHeight(int resolutionHeight) {
        this.resolutionHeight = resolutionHeight;
    }
}
