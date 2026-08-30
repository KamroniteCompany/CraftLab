package com.craftlab.craftlabcore.modpack;

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

/** Persiste les ModPack CURRENT et NEXT dans des fichiers séparés sous config/craftlabcore/modpack/. */
public class ModPackStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FOLDER_NAME = "craftlabcore";
    private static final String SUBFOLDER_NAME = "modpack";

    private final Path folderPath;

    public ModPackStorage() {
        this.folderPath = FMLPaths.CONFIGDIR.get().resolve(FOLDER_NAME).resolve(SUBFOLDER_NAME);
    }

    public ModPack loadCurrent() {
        return load(currentFile());
    }

    public ModPack loadNext() {
        return load(nextFile());
    }

    public void saveCurrent(ModPack pack) {
        save(currentFile(), pack);
    }

    public void saveNext(ModPack pack) {
        save(nextFile(), pack);
    }

    private Path currentFile() {
        return folderPath.resolve("current-modpack.json");
    }

    private Path nextFile() {
        return folderPath.resolve("next-modpack.json");
    }

    private ModPack load(Path filePath) {
        if (!Files.exists(filePath)) {
            return null;
        }

        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) {
                return null;
            }

            ModPack pack = new ModPack();
            pack.setMinecraftVersion(getString(root, "minecraftVersion", "?"));
            pack.setForgeVersion(getString(root, "forgeVersion", "?"));
            pack.setState(ModPackState.valueOf(getString(root, "state", "READY")));
            pack.setApplyState(ApplyState.valueOf(getString(root, "applyState", "NOT_READY")));
            pack.setGeneration(root.has("generation") ? root.get("generation").getAsLong() : 0L);

            if (root.has("mods") && root.get("mods").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("mods")) {
                    try {
                        JsonObject obj = element.getAsJsonObject();
                        pack.getMods().add(new ModPackEntry(
                            getString(obj, "modId", null),
                            getString(obj, "name", null),
                            getString(obj, "version", null),
                            getString(obj, "source", null),
                            getString(obj, "releaseTag", null),
                            obj.has("releaseId") ? obj.get("releaseId").getAsLong() : 0L,
                            getString(obj, "assetName", null),
                            getString(obj, "sha256", null),
                            obj.has("size") ? obj.get("size").getAsLong() : 0L,
                            ModPackEntryStatus.valueOf(getString(obj, "status", "READY"))
                        ));
                    } catch (IllegalArgumentException | ClassCastException ignored) {
                        // Entrée corrompue : ignorée plutôt que de faire planter tout le chargement.
                    }
                }
            }

            return pack;
        } catch (IOException | JsonParseException | IllegalArgumentException e) {
            return null;
        }
    }

    private void save(Path filePath, ModPack pack) {
        try {
            Files.createDirectories(filePath.getParent());
        } catch (IOException e) {
            return;
        }

        JsonObject root = new JsonObject();
        root.addProperty("minecraftVersion", pack.getMinecraftVersion());
        root.addProperty("forgeVersion", pack.getForgeVersion());
        root.addProperty("state", pack.getState().name());
        root.addProperty("applyState", pack.getApplyState().name());
        root.addProperty("generation", pack.getGeneration());

        JsonArray array = new JsonArray();
        for (ModPackEntry entry : pack.getMods()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("modId", entry.getModId());
            obj.addProperty("name", entry.getName());
            obj.addProperty("version", entry.getVersion());
            obj.addProperty("source", entry.getSource());
            obj.addProperty("releaseTag", entry.getReleaseTag());
            obj.addProperty("releaseId", entry.getReleaseId());
            obj.addProperty("assetName", entry.getAssetName());
            obj.addProperty("sha256", entry.getSha256());
            obj.addProperty("size", entry.getSize());
            obj.addProperty("status", entry.getStatus().name());
            array.add(obj);
        }
        root.add("mods", array);

        try (BufferedWriter writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException ignored) {
            // Pas bloquant ; la prochaine sauvegarde réessaiera.
        }
    }

    private static String getString(JsonObject obj, String key, String defaultValue) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : defaultValue;
    }
}
