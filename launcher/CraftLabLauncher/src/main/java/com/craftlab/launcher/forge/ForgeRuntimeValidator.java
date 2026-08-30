package com.craftlab.launcher.forge;

import com.craftlab.launcher.instance.InstancePaths;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * Vérifie qu'une installation Forge est réellement utilisable, au-delà de la simple présence du
 * fichier de version. Le version.json est écrit par ForgeInstaller AVANT l'exécution des
 * processors (patch/remap du client) : si l'un de ces processors échoue en cours de route (ce
 * qui fut le cas historiquement avec le bug BINPATCH \data\client.lzma, désormais corrigé dans
 * ForgeInstaller.resolveDataValue), le version.json reste présent sur disque mais le jar client
 * patché — celui qui contient réellement net/minecraft/client/Minecraft.class, requis par
 * ForgeProdLaunchHandler$Client / MinecraftLocator au lancement — n'a jamais été produit.
 * ForgeInstaller.isInstalled() considérerait alors Forge comme "déjà installé" indéfiniment,
 * sans jamais retenter l'installation, même une fois le bug corrigé côté launcher.
 *
 * Ce validateur relit le version.json Forge et vérifie que chaque bibliothèque qu'il déclare
 * comme "produite localement" (bloc downloads.artifact avec url vide — donc jamais téléchargée,
 * seulement écrite sur disque par les processors de install_profile.json) est bien présente,
 * et que le jar client patché contient bien net/minecraft/client/Minecraft.class.
 */
public final class ForgeRuntimeValidator {

    private static final String MINECRAFT_CLASS_ENTRY = "net/minecraft/client/Minecraft.class";
    private static final Gson GSON = new Gson();

    private final InstancePaths paths;

    public ForgeRuntimeValidator(InstancePaths paths) {
        this.paths = paths;
    }

    public Result validate(String minecraftVersion, String forgeVersion) {
        String forgeId = minecraftVersion + "-forge-" + forgeVersion;
        Path profilePath = paths.versionsDir().resolve(forgeId).resolve(forgeId + ".json");
        Path vanillaClientJar = paths.versionsDir().resolve(minecraftVersion).resolve(minecraftVersion + ".jar");

        if (!Files.exists(profilePath)) {
            return new Result(profilePath, vanillaClientJar, Files.exists(vanillaClientJar),
                null, false, null, false, false, List.of());
        }

        JsonObject profile;
        try {
            profile = GSON.fromJson(Files.readString(profilePath, StandardCharsets.UTF_8), JsonObject.class);
        } catch (IOException e) {
            return new Result(profilePath, vanillaClientJar, Files.exists(vanillaClientJar),
                null, false, null, false, false, List.of());
        }

        Path universalJar = null;
        Path patchedClientJar = null;
        List<Path> missingLocalLibraries = new ArrayList<>();

        if (profile != null && profile.has("libraries") && profile.get("libraries").isJsonArray()) {
            for (JsonElement element : profile.getAsJsonArray("libraries")) {
                JsonObject lib = element.getAsJsonObject();
                if (!lib.has("name") || !lib.has("downloads")) {
                    continue;
                }
                JsonObject downloads = lib.getAsJsonObject("downloads");
                if (!downloads.has("artifact")) {
                    continue;
                }
                JsonObject artifact = downloads.getAsJsonObject("artifact");
                if (!artifact.has("path")) {
                    continue;
                }

                String[] parts = lib.get("name").getAsString().split(":");
                Path libPath = paths.librariesDir().resolve(artifact.get("path").getAsString());
                boolean isForgeArtifact = parts.length >= 2 && parts[1].equals("forge");
                String classifier = parts.length >= 4 ? parts[3] : "";

                if (isForgeArtifact && classifier.equals("universal")) {
                    universalJar = libPath;
                }
                if (isForgeArtifact && classifier.equals("client")) {
                    patchedClientJar = libPath;
                }

                String url = artifact.has("url") && !artifact.get("url").isJsonNull() ? artifact.get("url").getAsString() : "";
                if (url.isBlank() && !Files.exists(libPath)) {
                    missingLocalLibraries.add(libPath);
                }
            }
        }

        boolean minecraftClassPresent = patchedClientJar != null
            && Files.exists(patchedClientJar)
            && containsEntry(patchedClientJar, MINECRAFT_CLASS_ENTRY);

        return new Result(profilePath, vanillaClientJar, Files.exists(vanillaClientJar),
            universalJar, universalJar != null && Files.exists(universalJar),
            patchedClientJar, patchedClientJar != null && Files.exists(patchedClientJar),
            minecraftClassPresent, missingLocalLibraries);
    }

    private boolean containsEntry(Path jar, String entryName) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.getEntry(entryName) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** Résultat immuable d'une validation. Toutes les infos nécessaires aux diagnostics de lancement. */
    public static final class Result {
        public final Path forgeVersionProfile;
        public final Path minecraftClientJar;
        public final boolean minecraftClientJarPresent;
        public final Path forgeUniversalJar;
        public final boolean forgeUniversalJarPresent;
        public final Path forgePatchedClientJar;
        public final boolean forgePatchedClientJarPresent;
        public final boolean minecraftClassPresent;
        public final List<Path> missingLocalLibraries;

        Result(Path forgeVersionProfile, Path minecraftClientJar, boolean minecraftClientJarPresent,
               Path forgeUniversalJar, boolean forgeUniversalJarPresent,
               Path forgePatchedClientJar, boolean forgePatchedClientJarPresent,
               boolean minecraftClassPresent, List<Path> missingLocalLibraries) {
            this.forgeVersionProfile = forgeVersionProfile;
            this.minecraftClientJar = minecraftClientJar;
            this.minecraftClientJarPresent = minecraftClientJarPresent;
            this.forgeUniversalJar = forgeUniversalJar;
            this.forgeUniversalJarPresent = forgeUniversalJarPresent;
            this.forgePatchedClientJar = forgePatchedClientJar;
            this.forgePatchedClientJarPresent = forgePatchedClientJarPresent;
            this.minecraftClassPresent = minecraftClassPresent;
            this.missingLocalLibraries = missingLocalLibraries;
        }

        /**
         * Le runtime Forge est prêt si le profil existe, le client patché (celui qui contient
         * réellement Minecraft.class, PAS le jar vanilla obfusqué) est présent et complet, et
         * qu'aucune bibliothèque locale déclarée par le profil n'est manquante.
         */
        public boolean isReady() {
            return Files.exists(forgeVersionProfile)
                && forgePatchedClientJarPresent
                && minecraftClassPresent
                && missingLocalLibraries.isEmpty();
        }
    }
}
