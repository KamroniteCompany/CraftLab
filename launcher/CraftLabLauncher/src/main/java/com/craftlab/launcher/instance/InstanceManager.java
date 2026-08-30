package com.craftlab.launcher.instance;

import com.craftlab.launcher.forge.ForgeInstaller;
import com.craftlab.launcher.runtime.MinecraftInstaller;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Orchestre la préparation complète de l'instance CraftLab : Minecraft vanilla d'abord (via
 * MinecraftInstaller, directement depuis l'API publique de Mojang), puis Forge (via
 * ForgeInstaller, qui en dépend). Ne s'occupe jamais des mods (voir SyncManager, inchangé) ni
 * du lancement lui-même (voir MinecraftLauncher, inchangé).
 */
public class InstanceManager {

    private final MinecraftInstaller minecraftInstaller;
    private final ForgeInstaller forgeInstaller;

    public InstanceManager(InstancePaths paths) {
        this.minecraftInstaller = new MinecraftInstaller(paths);
        this.forgeInstaller = new ForgeInstaller(paths);
    }

    public void ensureReady(String minecraftVersion, String forgeVersion, Consumer<String> onLog) throws IOException, InterruptedException {
        if (!minecraftInstaller.isInstalled(minecraftVersion)) {
            onLog.accept("Minecraft " + minecraftVersion + " absent de l'instance CraftLab, installation depuis Mojang...");
            minecraftInstaller.install(minecraftVersion, onLog);
        } else {
            onLog.accept("Minecraft " + minecraftVersion + " déjà présent.");
        }
        onLog.accept("[CraftLab] Minecraft vérifié.");

        if (!forgeInstaller.isInstalled(minecraftVersion, forgeVersion)) {
            onLog.accept("Forge " + forgeVersion + " absent, installation...");
            forgeInstaller.install(minecraftVersion, forgeVersion, onLog);
        } else {
            onLog.accept("Forge " + forgeVersion + " déjà installé.");
        }
        onLog.accept("[CraftLab] Forge vérifié.");

        onLog.accept("[CraftLab] Instance vérifiée.");
    }

    public String versionId(String minecraftVersion, String forgeVersion) {
        return forgeInstaller.versionId(minecraftVersion, forgeVersion);
    }
}
