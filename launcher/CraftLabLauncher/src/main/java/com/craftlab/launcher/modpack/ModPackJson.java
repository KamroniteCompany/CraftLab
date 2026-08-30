package com.craftlab.launcher.modpack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.util.ArrayList;
import java.util.List;

/** Parsing partagé du JSON de ModPack (même format que current-modpack-launcher.json côté serveur). */
final class ModPackJson {

    private static final Gson GSON = new GsonBuilder().create();

    private ModPackJson() {
    }

    static RemoteModPack parse(String body) {
        try {
            JsonObject root = GSON.fromJson(body, JsonObject.class);
            if (root == null) {
                throw new ModPackFetchException("Réponse ModPack vide ou invalide.");
            }

            List<RemoteModEntry> mods = new ArrayList<>();
            if (root.has("mods") && root.get("mods").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("mods")) {
                    JsonObject obj = element.getAsJsonObject();
                    mods.add(new RemoteModEntry(
                        getString(obj, "modId"),
                        getString(obj, "name"),
                        getString(obj, "version"),
                        getString(obj, "assetName"),
                        getString(obj, "sha256"),
                        obj.has("size") ? obj.get("size").getAsLong() : 0L,
                        getString(obj, "downloadUrl")
                    ));
                }
            }

            return new RemoteModPack(
                getString(root, "minecraftVersion"),
                getString(root, "forgeVersion"),
                root.has("generation") ? root.get("generation").getAsLong() : 0L,
                mods
            );
        } catch (JsonParseException | IllegalStateException e) {
            throw new ModPackFetchException("Réponse ModPack illisible : " + e.getMessage());
        }
    }

    private static String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }
}
