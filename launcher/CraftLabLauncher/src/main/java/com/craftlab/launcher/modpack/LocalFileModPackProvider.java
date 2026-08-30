package com.craftlab.launcher.modpack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Lit un ModPack depuis un fichier local (même format que HttpModPackProvider), pour tester sans serveur HTTP. */
public class LocalFileModPackProvider implements ModPackProvider {

    private final Path filePath;

    public LocalFileModPackProvider(Path filePath) {
        this.filePath = filePath;
    }

    @Override
    public CompletableFuture<RemoteModPack> getCurrentModPack() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String content = Files.readString(filePath);
                return ModPackJson.parse(content);
            } catch (Exception e) {
                throw new ModPackFetchException("Impossible de lire le ModPack local (" + filePath + ") : " + e.getMessage());
            }
        });
    }
}
