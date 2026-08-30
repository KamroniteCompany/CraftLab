package com.craftlab.launcher.instance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Persiste ce que le launcher considère actuellement comme installé, dans instances/craftlab/installed-modpack.json. */
public class InstalledModPackStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path filePath;

    public InstalledModPackStorage(InstancePaths paths) {
        this.filePath = paths.installedModPackFile();
    }

    /** Retourne null si aucune installation n'est encore enregistrée (premier lancement). */
    public InstalledModPack load() {
        if (!Files.exists(filePath)) {
            return null;
        }
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) {
                return null;
            }
            List<InstalledModEntry> mods = new ArrayList<>();
            if (root.has("mods") && root.get("mods").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("mods")) {
                    JsonObject obj = element.getAsJsonObject();
                    mods.add(new InstalledModEntry(
                        getString(obj, "modId"),
                        getString(obj, "version"),
                        getString(obj, "assetName"),
                        getString(obj, "sha256"),
                        obj.has("size") ? obj.get("size").getAsLong() : 0L
                    ));
                }
            }
            return new InstalledModPack(
                getString(root, "minecraftVersion"),
                getString(root, "forgeVersion"),
                root.has("generation") ? root.get("generation").getAsLong() : 0L,
                mods
            );
        } catch (IOException | JsonParseException e) {
            return null;
        }
    }

    public void save(InstalledModPack pack) {
        try {
            Files.createDirectories(filePath.getParent());
        } catch (IOException e) {
            return;
        }

        JsonObject root = new JsonObject();
        root.addProperty("minecraftVersion", pack.minecraftVersion());
        root.addProperty("forgeVersion", pack.forgeVersion());
        root.addProperty("generation", pack.generation());

        JsonArray mods = new JsonArray();
        for (InstalledModEntry entry : pack.mods()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("modId", entry.modId());
            obj.addProperty("version", entry.version());
            obj.addProperty("assetName", entry.assetName());
            obj.addProperty("sha256", entry.sha256());
            obj.addProperty("size", entry.size());
            mods.add(obj);
        }
        root.add("mods", mods);

        try (BufferedWriter writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException ignored) {
            // Pas bloquant ; la prochaine synchronisation réessaiera.
        }
    }

    private static String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }
}
