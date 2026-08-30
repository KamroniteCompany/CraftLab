package com.craftlab.launcher.runtime;

import com.craftlab.launcher.instance.InstancePaths;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Prépare une installation Minecraft vanilla "propre" directement depuis l'API publique de
 * Mojang (piston-meta), sans dépendre d'aucune installation ni d'aucun lancement préalable du
 * launcher officiel. N'écrit que dans l'instance CraftLab isolée (instanceDir()), jamais dans
 * le .minecraft principal de l'utilisateur. Deuxième pièce de l'architecture
 * MinecraftInstaller / ForgeInstaller / InstanceManager (voir InstanceManager pour
 * l'orchestration des deux).
 *
 * Utilise SHA-1 pour la vérification, car c'est le format fourni par le manifest Mojang
 * lui-même (contrairement au SHA-256 utilisé pour la vérification des mods CraftLab, qui
 * reste inchangée — voir ModDownloader).
 */
public class MinecraftInstaller {

    private static final String VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String ASSETS_CDN = "https://resources.download.minecraft.net";
    private static final int ASSET_DOWNLOAD_CONCURRENCY = 8;

    private final InstancePaths paths;
    private final HttpClient httpClient;

    public MinecraftInstaller(InstancePaths paths) {
        this.paths = paths;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public boolean isInstalled(String minecraftVersion) {
        Path versionJson = paths.versionsDir().resolve(minecraftVersion).resolve(minecraftVersion + ".json");
        Path versionJar = paths.versionsDir().resolve(minecraftVersion).resolve(minecraftVersion + ".jar");
        return Files.exists(versionJson) && Files.exists(versionJar);
    }

    public void install(String minecraftVersion, Consumer<String> onLog) throws IOException, InterruptedException {
        onLog.accept("Recherche de Minecraft " + minecraftVersion + " dans le manifest Mojang...");
        String versionMetaUrl = findVersionUrl(minecraftVersion);

        JsonObject versionJson = fetchJson(versionMetaUrl);
        Path versionDir = paths.versionsDir().resolve(minecraftVersion);
        Files.createDirectories(versionDir);
        Files.writeString(versionDir.resolve(minecraftVersion + ".json"), versionJson.toString(), StandardCharsets.UTF_8);

        onLog.accept("Téléchargement du client Minecraft " + minecraftVersion + "...");
        JsonObject clientDownload = versionJson.getAsJsonObject("downloads").getAsJsonObject("client");
        Path clientJar = versionDir.resolve(minecraftVersion + ".jar");
        downloadAndVerify(clientDownload.get("url").getAsString(), clientJar,
            clientDownload.has("sha1") ? clientDownload.get("sha1").getAsString() : null);

        onLog.accept("Téléchargement des bibliothèques vanilla...");
        downloadLibraries(versionJson);

        onLog.accept("Téléchargement des assets (peut prendre plusieurs minutes au premier lancement)...");
        downloadAssets(versionJson, onLog);

        onLog.accept("Minecraft " + minecraftVersion + " installé.");
    }

    private String findVersionUrl(String minecraftVersion) throws IOException, InterruptedException {
        JsonObject manifest = fetchJson(VERSION_MANIFEST_URL);
        for (JsonElement element : manifest.getAsJsonArray("versions")) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.get("id").getAsString().equals(minecraftVersion)) {
                return entry.get("url").getAsString();
            }
        }
        throw new IOException("Version Minecraft introuvable dans le manifest Mojang : " + minecraftVersion);
    }

    private void downloadLibraries(JsonObject versionJson) throws IOException, InterruptedException {
        if (!versionJson.has("libraries")) {
            return;
        }
        for (JsonElement element : versionJson.getAsJsonArray("libraries")) {
            JsonObject lib = element.getAsJsonObject();
            if (!rulesAllow(lib)) {
                continue;
            }
            if (!lib.has("downloads") || !lib.getAsJsonObject("downloads").has("artifact")) {
                continue;
            }
            JsonObject artifact = lib.getAsJsonObject("downloads").getAsJsonObject("artifact");
            if (!artifact.has("path") || !artifact.has("url")) {
                continue;
            }
            Path target = paths.librariesDir().resolve(artifact.get("path").getAsString());
            if (Files.exists(target)) {
                continue;
            }
            Files.createDirectories(target.getParent());
            downloadAndVerify(artifact.get("url").getAsString(), target,
                artifact.has("sha1") ? artifact.get("sha1").getAsString() : null);
        }
    }

    private void downloadAssets(JsonObject versionJson, Consumer<String> onLog) throws IOException, InterruptedException {
        JsonObject assetIndexRef = versionJson.getAsJsonObject("assetIndex");
        String indexId = assetIndexRef.get("id").getAsString();
        Path indexPath = paths.assetsDir().resolve("indexes").resolve(indexId + ".json");
        Files.createDirectories(indexPath.getParent());
        JsonObject assetIndex = fetchJson(assetIndexRef.get("url").getAsString());
        Files.writeString(indexPath, assetIndex.toString(), StandardCharsets.UTF_8);

        JsonObject objects = assetIndex.getAsJsonObject("objects");
        List<Runnable> tasks = new ArrayList<>();
        // L'index d'assets Mojang référence le même contenu (même hash) sous plusieurs noms de
        // fichier (ex. plusieurs sons/textures identiques). Sans dédoublonnage par hash, deux
        // tâches concurrentes viseraient le même fichier ".part" : la première à terminer le
        // déplace vers sa cible, et le move de la seconde échoue alors avec
        // NoSuchFileException — un plantage aléatoire de l'installation, plus probable sur un
        // pool de threads que sur des téléchargements séquentiels.
        java.util.Set<String> scheduledHashes = new java.util.HashSet<>();
        for (var entryObj : objects.entrySet()) {
            JsonObject obj = entryObj.getValue().getAsJsonObject();
            String hash = obj.get("hash").getAsString();
            String prefix = hash.substring(0, 2);
            Path target = paths.assetsDir().resolve("objects").resolve(prefix).resolve(hash);
            if (Files.exists(target)) {
                continue; // existence seule : re-vérifier le SHA-1 de milliers de petits fichiers
                          // à chaque lancement serait disproportionné pour cette version.
            }
            if (!scheduledHashes.add(hash)) {
                continue; // déjà programmé sous un autre nom d'asset référençant le même contenu
            }
            tasks.add(() -> {
                try {
                    Files.createDirectories(target.getParent());
                    downloadAndVerify(ASSETS_CDN + "/" + prefix + "/" + hash, target, null);
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            });
        }

        onLog.accept(tasks.size() + " nouveaux assets à télécharger (sur " + objects.entrySet().size() + " au total).");
        runConcurrently(tasks);
    }

    private void runConcurrently(List<Runnable> tasks) throws IOException {
        if (tasks.isEmpty()) {
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(ASSET_DOWNLOAD_CONCURRENCY, r -> {
            Thread t = new Thread(r, "CraftLab-AssetDownload");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable task : tasks) {
                futures.add(pool.submit(task));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (Exception e) {
            throw new IOException("Échec du téléchargement des assets : " + e.getMessage(), e);
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean rulesAllow(JsonObject withRules) {
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
                    matches = os.get("name").getAsString().equalsIgnoreCase(detectOs());
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

    private JsonObject fetchJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Échec de récupération de " + url + " (code " + response.statusCode() + ").");
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private void downloadAndVerify(String url, Path target, String expectedSha1) throws IOException, InterruptedException {
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        Files.createDirectories(target.getParent());
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMinutes(2))
            .GET()
            .build();
        HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(temp,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(temp);
            throw new IOException("Téléchargement échoué pour " + url + " (code " + response.statusCode() + ").");
        }
        if (expectedSha1 != null) {
            String actual = sha1(temp);
            if (!expectedSha1.equalsIgnoreCase(actual)) {
                Files.deleteIfExists(temp);
                throw new IOException("SHA-1 invalide pour " + url);
            }
        }
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
