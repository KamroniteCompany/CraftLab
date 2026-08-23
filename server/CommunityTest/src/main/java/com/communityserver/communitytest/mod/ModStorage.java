package com.communityserver.communitytest.mod;

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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persiste le registre des mods dans config/communitytest/mods.json.
 * Format : tableau JSON d'objets mod, pour rester lisible/éditable à la main.
 * "source" et "release" sont des objets imbriqués optionnels, absents pour un mod
 * enregistré manuellement (voir ModSource / ModReleaseInfo) — un ancien mods.json
 * sans ces clés reste donc parfaitement lisible.
 */
public class ModStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FOLDER_NAME = "communitytest";
    private static final String FILE_NAME = "mods.json";

    private final Path filePath;

    public ModStorage() {
        this.filePath = FMLPaths.CONFIGDIR.get().resolve(FOLDER_NAME).resolve(FILE_NAME);
    }

    public boolean exists() {
        return Files.exists(filePath);
    }

    public Map<String, ModDefinition> load() {
        Map<String, ModDefinition> result = new LinkedHashMap<>();
        if (!Files.exists(filePath)) {
            return result;
        }

        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            JsonElement root = GSON.fromJson(reader, JsonElement.class);
            if (root == null || !root.isJsonArray()) {
                return result;
            }

            for (JsonElement element : root.getAsJsonArray()) {
                try {
                    JsonObject obj = element.getAsJsonObject();
                    ModDefinition mod = new ModDefinition();
                    mod.setId(getString(obj, "id", null));
                    mod.setName(getString(obj, "name", mod.getId()));
                    mod.setAuthor(getString(obj, "author", "?"));
                    mod.setVersion(getString(obj, "version", "?"));
                    mod.setDescription(getString(obj, "description", ""));
                    mod.setStatus(ModStatus.valueOf(getString(obj, "status", "TESTING")));
                    mod.setSource(readSource(obj));
                    mod.setRelease(readRelease(obj));

                    if (mod.getId() != null) {
                        result.put(mod.getId(), mod);
                    }
                } catch (IllegalArgumentException | ClassCastException ignored) {
                    // Entrée corrompue : ignorée plutôt que de faire planter tout le chargement.
                }
            }
        } catch (IOException | JsonParseException e) {
            return new LinkedHashMap<>();
        }

        return result;
    }

    public void save(Collection<ModDefinition> mods) {
        try {
            Files.createDirectories(filePath.getParent());
        } catch (IOException e) {
            return;
        }

        JsonArray array = new JsonArray();
        for (ModDefinition mod : mods) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", mod.getId());
            obj.addProperty("name", mod.getName());
            obj.addProperty("author", mod.getAuthor());
            obj.addProperty("version", mod.getVersion());
            obj.addProperty("description", mod.getDescription());
            obj.addProperty("status", mod.getStatus().name());
            writeSource(obj, mod.getSource());
            writeRelease(obj, mod.getRelease());
            array.add(obj);
        }

        try (BufferedWriter writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
            GSON.toJson(array, writer);
        } catch (IOException ignored) {
            // Pas bloquant ; la prochaine sauvegarde réessaiera.
        }
    }

    private ModSource readSource(JsonObject obj) {
        if (!obj.has("source") || !obj.get("source").isJsonObject()) {
            return null;
        }
        JsonObject sourceObj = obj.getAsJsonObject("source");
        return new ModSource(
            getString(sourceObj, "type", ModSource.TYPE_GITHUB),
            getString(sourceObj, "owner", null),
            getString(sourceObj, "repository", null),
            getString(sourceObj, "repositoryUrl", null)
        );
    }

    private void writeSource(JsonObject obj, ModSource source) {
        if (source == null) {
            return;
        }
        JsonObject sourceObj = new JsonObject();
        sourceObj.addProperty("type", source.getType());
        sourceObj.addProperty("owner", source.getOwner());
        sourceObj.addProperty("repository", source.getRepository());
        sourceObj.addProperty("repositoryUrl", source.getRepositoryUrl());
        obj.add("source", sourceObj);
    }

    private ModReleaseInfo readRelease(JsonObject obj) {
        if (!obj.has("release") || !obj.get("release").isJsonObject()) {
            return null;
        }
        JsonObject releaseObj = obj.getAsJsonObject("release");
        return new ModReleaseInfo(
            getString(releaseObj, "tag", null),
            releaseObj.has("releaseId") ? releaseObj.get("releaseId").getAsLong() : 0L,
            getString(releaseObj, "assetName", null),
            getString(releaseObj, "assetDownloadUrl", null),
            getString(releaseObj, "expectedSha256", null)
        );
    }

    private void writeRelease(JsonObject obj, ModReleaseInfo release) {
        if (release == null) {
            return;
        }
        JsonObject releaseObj = new JsonObject();
        releaseObj.addProperty("tag", release.getTag());
        releaseObj.addProperty("releaseId", release.getReleaseId());
        releaseObj.addProperty("assetName", release.getAssetName());
        releaseObj.addProperty("assetDownloadUrl", release.getAssetDownloadUrl());
        if (release.getExpectedSha256() != null) {
            releaseObj.addProperty("expectedSha256", release.getExpectedSha256());
        }
        obj.add("release", releaseObj);
    }

    private static String getString(JsonObject obj, String key, String defaultValue) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : defaultValue;
    }
}
