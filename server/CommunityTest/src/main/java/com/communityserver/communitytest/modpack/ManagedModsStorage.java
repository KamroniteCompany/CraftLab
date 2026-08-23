package com.communityserver.communitytest.modpack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persiste le manifest des mods gérés dans config/communitytest/managed-mods.json.
 * Supporte aussi un chemin explicite, utilisé pour lire/écrire une copie de ce manifest
 * dans un dossier de backup (voir ModPackBackupManager).
 */
public class ManagedModsStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path filePath;

    public ManagedModsStorage() {
        this.filePath = FMLPaths.CONFIGDIR.get().resolve("communitytest").resolve("managed-mods.json");
    }

    public Path path() {
        return filePath;
    }

    public ManagedModsManifest load() {
        return load(filePath);
    }

    public ManagedModsManifest load(Path path) {
        ManagedModsManifest manifest = new ManagedModsManifest();
        if (!Files.exists(path)) {
            return manifest;
        }

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root != null && root.has("mods") && root.get("mods").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("mods")) {
                    try {
                        JsonObject obj = element.getAsJsonObject();
                        manifest.getMods().add(new ManagedModEntry(
                            getString(obj, "modId", null),
                            getString(obj, "file", null),
                            getString(obj, "sha256", null)
                        ));
                    } catch (ClassCastException ignored) {
                        // Entrée corrompue : ignorée.
                    }
                }
            }
        } catch (IOException | JsonParseException e) {
            return new ManagedModsManifest();
        }

        return manifest;
    }

    public void save(ManagedModsManifest manifest) {
        save(manifest, filePath);
    }

    public void save(ManagedModsManifest manifest, Path path) {
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            return;
        }

        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        for (ManagedModEntry entry : manifest.getMods()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("modId", entry.getModId());
            obj.addProperty("file", entry.getFile());
            obj.addProperty("sha256", entry.getSha256());
            array.add(obj);
        }
        root.add("mods", array);

        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException ignored) {
            // Pas bloquant ; la prochaine sauvegarde réessaiera.
        }
    }

    private static String getString(JsonObject obj, String key, String defaultValue) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : defaultValue;
    }
}
