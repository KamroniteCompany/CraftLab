package com.craftlab.launcher.version;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lit et fusionne les fichiers de version JSON produits par l'installeur officiel de Forge
 * (format standard Mojang : un fichier enfant avec "inheritsFrom" pointant vers le parent
 * vanilla). Ne télécharge rien : l'installeur Forge a déjà placé toutes les bibliothèques,
 * le client vanilla et les assets nécessaires sur le disque — ce resolver se contente de
 * lire cette arborescence pour construire le classpath et les arguments de lancement.
 *
 * Les entrées de "arguments.game"/"arguments.jvm" peuvent être conditionnées par des règles
 * de DEUX types, comme dans le vrai launcher Mojang : "os" (déjà géré) et "features" — un
 * argument comme --width/--height n'est inclus QUE si la feature "has_custom_resolution" est
 * active ; --quickPlaySingleplayer QUE si "is_quick_play_singleplayer" l'est, etc. Ignorer
 * "features" (comme le faisait ce fichier avant) revient à TOUJOURS inclure ces arguments,
 * avec leurs placeholders jamais résolus faute de fonctionnalité correspondante côté
 * CraftLab — c'est la cause du crash NumberFormatException sur ${resolution_width}.
 *
 * Les bibliothèques LWJGL modernes (1.19+) sont de simples JAR de classpath, sans extraction
 * de natives nécessaire côté launcher — ${natives_directory} pointe vers un dossier vide créé
 * par précaution, au cas où une bibliothèque plus ancienne en aurait besoin.
 */
public class VersionManifestResolver {

    private static final Gson GSON = new GsonBuilder().create();
    private static final String CURRENT_OS = detectOs();

    private final Path versionsDir;
    private final Path librariesDir;

    public VersionManifestResolver(Path versionsDir, Path librariesDir) {
        this.versionsDir = versionsDir;
        this.librariesDir = librariesDir;
    }

    public ResolvedVersion resolve(String versionId, Set<String> enabledFeatures) throws IOException {
        JsonObject child = readVersionJson(versionId);
        JsonObject parent = null;
        if (child.has("inheritsFrom")) {
            parent = readVersionJson(child.get("inheritsFrom").getAsString());
        }

        String mainClass = child.has("mainClass") ? child.get("mainClass").getAsString()
            : (parent != null && parent.has("mainClass") ? parent.get("mainClass").getAsString() : null);
        String assetsId = firstNonNull(getString(child, "assets"), parent != null ? getString(parent, "assets") : null);

        List<String> classpath = new ArrayList<>();
        if (parent != null) {
            collectLibraries(parent, classpath);
        }
        collectLibraries(child, classpath);

        String baseVersionId = parent != null ? parent.get("id").getAsString() : versionId;
        Path clientJar = versionsDir.resolve(baseVersionId).resolve(baseVersionId + ".jar");
        if (Files.exists(clientJar)) {
            classpath.add(clientJar.toAbsolutePath().toString());
        }

        List<String> jvmArgs = new ArrayList<>();
        List<String> gameArgs = new ArrayList<>();
        if (parent != null) {
            collectArguments(parent, jvmArgs, gameArgs, enabledFeatures);
        }
        collectArguments(child, jvmArgs, gameArgs, enabledFeatures);

        return new ResolvedVersion(versionId, mainClass, assetsId, classpath, gameArgs, jvmArgs);
    }

    private JsonObject readVersionJson(String versionId) throws IOException {
        Path path = versionsDir.resolve(versionId).resolve(versionId + ".json");
        if (!Files.exists(path)) {
            throw new IOException("Fichier de version introuvable : " + path
                + " (Forge est-il bien installé ? voir ForgeInstaller)");
        }
        String content = Files.readString(path, StandardCharsets.UTF_8);
        JsonObject obj = GSON.fromJson(content, JsonObject.class);
        if (obj == null) {
            throw new IOException("Fichier de version illisible : " + path);
        }
        return obj;
    }

    private void collectLibraries(JsonObject versionJson, List<String> classpath) {
        if (!versionJson.has("libraries") || !versionJson.get("libraries").isJsonArray()) {
            return;
        }
        for (JsonElement element : versionJson.getAsJsonArray("libraries")) {
            JsonObject lib = element.getAsJsonObject();
            if (!rulesAllow(lib, Set.of())) {
                continue;
            }
            String relativePath = extractLibraryPath(lib);
            if (relativePath == null) {
                continue;
            }
            Path libPath = librariesDir.resolve(relativePath);
            if (Files.exists(libPath)) {
                classpath.add(libPath.toAbsolutePath().toString());
            }
        }
    }

    private String extractLibraryPath(JsonObject lib) {
        if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("artifact")) {
            JsonObject artifact = lib.getAsJsonObject("downloads").getAsJsonObject("artifact");
            if (artifact.has("path")) {
                return artifact.get("path").getAsString();
            }
        }
        // Certaines bibliothèques Forge ne fournissent que "name" (coordonnées Maven), sans
        // bloc "downloads" explicite : on reconstruit le chemin Maven standard.
        if (lib.has("name")) {
            return mavenCoordinatesToPath(lib.get("name").getAsString());
        }
        return null;
    }

    private String mavenCoordinatesToPath(String coordinates) {
        try {
            return com.craftlab.launcher.maven.MavenCoordinates.toRelativePath(coordinates);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void collectArguments(JsonObject versionJson, List<String> jvmArgs, List<String> gameArgs, Set<String> enabledFeatures) {
        if (versionJson.has("arguments")) {
            JsonObject arguments = versionJson.getAsJsonObject("arguments");
            if (arguments.has("jvm")) {
                collectArgumentArray(arguments.getAsJsonArray("jvm"), jvmArgs, enabledFeatures);
            }
            if (arguments.has("game")) {
                collectArgumentArray(arguments.getAsJsonArray("game"), gameArgs, enabledFeatures);
            }
        } else if (versionJson.has("minecraftArguments")) {
            // Ancien format (pré-1.13) : non attendu pour 1.21.1, conservé par robustesse.
            for (String token : versionJson.get("minecraftArguments").getAsString().split(" ")) {
                gameArgs.add(token);
            }
        }
    }

    private void collectArgumentArray(JsonArray array, List<String> out, Set<String> enabledFeatures) {
        for (JsonElement element : array) {
            if (element.isJsonPrimitive()) {
                out.add(element.getAsString());
            } else if (element.isJsonObject()) {
                JsonObject rule = element.getAsJsonObject();
                if (!rulesAllow(rule, enabledFeatures)) {
                    continue;
                }
                JsonElement value = rule.get("value");
                if (value == null) {
                    continue;
                }
                if (value.isJsonArray()) {
                    for (JsonElement v : value.getAsJsonArray()) {
                        out.add(v.getAsString());
                    }
                } else {
                    out.add(value.getAsString());
                }
            }
        }
    }

    /**
     * Évalue le tableau "rules" d'une entrée (bibliothèque ou argument), en tenant compte à la
     * fois de "os" et de "features". Une règle avec "features" n'est satisfaite QUE si chaque
     * feature qu'elle exige correspond exactement (activée si vrai attendu, absente si faux
     * attendu) à enabledFeatures — sinon l'entrée entière est ignorée, exactement comme le
     * launcher Mojang officiel le fait pour --width/--height, --quickPlay*, etc.
     */
    private boolean rulesAllow(JsonObject withRules, Set<String> enabledFeatures) {
        if (!withRules.has("rules") || !withRules.get("rules").isJsonArray()) {
            return true;
        }
        boolean allowed = false;
        for (JsonElement element : withRules.getAsJsonArray("rules")) {
            JsonObject rule = element.getAsJsonObject();
            String action = rule.has("action") ? rule.get("action").getAsString() : "allow";
            boolean matches = true;

            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                if (os.has("name")) {
                    matches = os.get("name").getAsString().equalsIgnoreCase(CURRENT_OS);
                }
            }

            if (matches && rule.has("features")) {
                JsonObject features = rule.getAsJsonObject("features");
                for (var featureEntry : features.entrySet()) {
                    boolean required = featureEntry.getValue().getAsBoolean();
                    boolean enabled = enabledFeatures.contains(featureEntry.getKey());
                    if (required != enabled) {
                        matches = false;
                        break;
                    }
                }
            }

            if (matches) {
                allowed = action.equals("allow");
            }
        }
        return allowed;
    }

    private static String detectOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac")) {
            return "osx";
        }
        return "linux";
    }

    private static String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
