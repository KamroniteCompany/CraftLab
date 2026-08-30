package com.craftlab.craftlabcore.modpack;

import com.craftlab.craftlabcore.mod.ModDefinition;
import com.craftlab.craftlabcore.mod.ModRegistry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Génère config/craftlabcore/modpack/current-modpack-launcher.json : une projection en
 * lecture seule de CURRENT, enrichie d'une "downloadUrl" par mod (issue de
 * ModDefinition.release, absente du ModPack lui-même). C'est la SEULE information dont le
 * CraftLab Launcher a besoin et que le format existant ne contient pas — pas un nouveau
 * format parallèle, juste cette projection. Ne modifie ni ModRegistry ni ModPack ; lecture
 * seule des deux, écriture d'un unique fichier supplémentaire.
 */
public final class LauncherModPackExporter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private LauncherModPackExporter() {
    }

    public static void export(ModPack current) {
        Path filePath = FMLPaths.CONFIGDIR.get().resolve("craftlabcore").resolve("modpack")
            .resolve("current-modpack-launcher.json");

        JsonObject root = new JsonObject();
        root.addProperty("minecraftVersion", current.getMinecraftVersion());
        root.addProperty("forgeVersion", current.getForgeVersion());
        root.addProperty("generation", current.getGeneration());

        JsonArray mods = new JsonArray();
        for (ModPackEntry entry : current.getMods()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("modId", entry.getModId());
            obj.addProperty("name", entry.getName());
            obj.addProperty("version", entry.getVersion());
            obj.addProperty("assetName", entry.getAssetName());
            obj.addProperty("sha256", entry.getSha256());
            obj.addProperty("size", entry.getSize());

            String downloadUrl = ModRegistry.get().get(entry.getModId())
                .map(ModDefinition::getRelease)
                .map(release -> release == null ? null : release.getAssetDownloadUrl())
                .orElse(null);
            if (downloadUrl != null) {
                obj.addProperty("downloadUrl", downloadUrl);
            }

            mods.add(obj);
        }
        root.add("mods", mods);

        try {
            Files.createDirectories(filePath.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException ignored) {
            // Pas bloquant pour l'application du ModPack elle-même : le launcher retentera
            // simplement de lire un fichier à jour la prochaine fois.
        }
    }
}
