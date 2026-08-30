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
 */
public final class LauncherConfig {

    private static final String FILE_NAME = "launcher.properties";

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
        Properties properties = new Properties();
        if (Files.exists(filePath)) {
            try (InputStream in = Files.newInputStream(filePath)) {
                properties.load(in);
            } catch (IOException ignored) {
                // On repart des valeurs par défaut ci-dessous.
            }
        }

        // Par défaut, un fichier local de test — remplace par une vraie URL HTTPS servant
        // current-modpack-launcher.json une fois le serveur accessible depuis l'extérieur.
        modPackUrl = properties.getProperty("modpack_url", "file:./current-modpack-launcher.json");
        serverAddress = properties.getProperty("server_address", "localhost");
        serverPort = parseInt(properties.getProperty("server_port"), 25565);
        username = properties.getProperty("username", "Player");
        resolutionWidth = parseInt(properties.getProperty("resolution_width"), 1280);
        resolutionHeight = parseInt(properties.getProperty("resolution_height"), 720);

        save(); // garantit que le fichier existe avec toutes les clés, modifiable à la main
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
