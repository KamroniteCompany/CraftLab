package com.craftlab.launcher.instance;

import java.nio.file.Path;

/**
 * Centralise l'arborescence isolée du CraftLab Launcher. N'écrit jamais dans le .minecraft
 * principal de l'utilisateur : tout vit sous un dossier CraftLabLauncher dédié dans le
 * profil utilisateur (%APPDATA% sous Windows).
 *
 * instanceDir() est structurellement l'équivalent d'un .minecraft (versions/, libraries/,
 * assets/, mods/, natives/) : l'installeur Forge officiel y écrit directement quand on lui
 * passe --installClient <instanceDir>.
 */
public final class InstancePaths {

    private final Path root;

    private InstancePaths(Path root) {
        this.root = root;
    }

    /** %APPDATA%\CraftLabLauncher sous Windows ; ~/.craftlab-launcher ailleurs. */
    public static InstancePaths resolveDefault() {
        String os = System.getProperty("os.name", "").toLowerCase();
        Path base;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            base = Path.of(appData != null ? appData : System.getProperty("user.home")).resolve("CraftLabLauncher");
        } else {
            base = Path.of(System.getProperty("user.home")).resolve(".craftlab-launcher");
        }
        return new InstancePaths(base);
    }

    /** Pour les tests : pointe l'arborescence complète vers un répertoire arbitraire (ex. un dossier temporaire). */
    public static InstancePaths at(Path root) {
        return new InstancePaths(root);
    }

    public Path root() {
        return root;
    }

    public Path instanceDir() {
        return root.resolve("instances").resolve("craftlab");
    }

    public Path modsDir() {
        return instanceDir().resolve("mods");
    }

    public Path versionsDir() {
        return instanceDir().resolve("versions");
    }

    public Path librariesDir() {
        return instanceDir().resolve("libraries");
    }

    public Path assetsDir() {
        return instanceDir().resolve("assets");
    }

    public Path nativesDir(String versionId) {
        return instanceDir().resolve("natives").resolve(versionId);
    }

    public Path downloadsDir() {
        return root.resolve("downloads");
    }

    public Path stagingDir() {
        return root.resolve("staging");
    }

    public Path logsDir() {
        return root.resolve("logs");
    }

    public Path installedModPackFile() {
        return instanceDir().resolve("installed-modpack.json");
    }
}
