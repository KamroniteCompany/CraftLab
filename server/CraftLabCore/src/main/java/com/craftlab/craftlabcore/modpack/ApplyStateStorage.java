package com.craftlab.craftlabcore.modpack;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Marqueur minimal (texte brut, pas JSON) utilisé pour détecter une application de ModPack
 * interrompue en plein vol (crash, kill -9, coupure) au redémarrage suivant. Écrit juste avant
 * l'étape risquée (le remplacement des fichiers dans /mods) et effacé juste après.
 */
public class ApplyStateStorage {

    private final Path filePath;

    public ApplyStateStorage() {
        this.filePath = FMLPaths.CONFIGDIR.get().resolve("craftlabcore").resolve("modpack").resolve("apply-state.txt");
    }

    /** Pour les tests : évite l'appel à FMLPaths (indisponible hors environnement Forge/FML). */
    ApplyStateStorage(Path filePath) {
        this.filePath = filePath;
    }

    public ApplyState load() {
        if (!Files.exists(filePath)) {
            return ApplyState.NOT_READY;
        }
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8).trim();
            return ApplyState.valueOf(content);
        } catch (IOException | IllegalArgumentException e) {
            return ApplyState.NOT_READY;
        }
    }

    public void markApplying() {
        write(ApplyState.APPLYING);
    }

    public void markApplied() {
        write(ApplyState.APPLIED);
    }

    public void markFailed() {
        write(ApplyState.FAILED);
    }

    private void write(ApplyState state) {
        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, state.name(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Best-effort : le pire cas est un état non détecté au prochain démarrage.
        }
    }
}
