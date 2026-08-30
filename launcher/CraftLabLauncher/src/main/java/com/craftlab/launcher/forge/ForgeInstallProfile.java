package com.craftlab.launcher.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Représentation structurée de install_profile.json, extrait du jar installeur Forge — pure
 * donnée, aucune logique d'exécution ni d'accès disque/réseau (voir ForgeInstaller pour ça).
 */
public final class ForgeInstallProfile {

    private final String versionJsonPath;
    private final Map<String, String> rawData;
    private final List<ForgeInstallStep> steps;
    private final JsonArray libraries;

    private ForgeInstallProfile(String versionJsonPath, Map<String, String> rawData, List<ForgeInstallStep> steps, JsonArray libraries) {
        this.versionJsonPath = versionJsonPath;
        this.rawData = rawData;
        this.steps = steps;
        this.libraries = libraries;
    }

    public static ForgeInstallProfile parse(JsonObject json) {
        String versionJsonPath = json.has("json") ? json.get("json").getAsString() : "/version.json";

        Map<String, String> rawData = new LinkedHashMap<>();
        if (json.has("data")) {
            for (var entry : json.getAsJsonObject("data").entrySet()) {
                JsonObject perSide = entry.getValue().getAsJsonObject();
                if (perSide.has("client")) {
                    rawData.put(entry.getKey(), perSide.get("client").getAsString());
                }
            }
        }

        List<ForgeInstallStep> steps = new ArrayList<>();
        if (json.has("processors")) {
            for (JsonElement element : json.getAsJsonArray("processors")) {
                steps.add(ForgeInstallStep.parse(element.getAsJsonObject()));
            }
        }

        JsonArray libraries = json.has("libraries") ? json.getAsJsonArray("libraries") : new JsonArray();

        return new ForgeInstallProfile(versionJsonPath, rawData, steps, libraries);
    }

    /** Chemin (interne au jar installeur) vers le profil de version Forge final, ex. "/version.json". */
    public String versionJsonPath() {
        return versionJsonPath;
    }

    /** Valeurs "client" brutes (non résolues) de la section "data", indexées par nom de token. */
    public Map<String, String> rawData() {
        return rawData;
    }

    public List<ForgeInstallStep> steps() {
        return steps;
    }

    /** Bibliothèques nécessaires à l'exécution des étapes elles-mêmes (distinct des bibliothèques de jeu du version.json). */
    public JsonArray libraries() {
        return libraries;
    }
}
