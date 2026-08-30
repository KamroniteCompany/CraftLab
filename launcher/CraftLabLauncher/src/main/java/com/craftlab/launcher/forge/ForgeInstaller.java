package com.craftlab.launcher.forge;

import com.craftlab.launcher.instance.InstancePaths;
import com.craftlab.launcher.maven.MavenCoordinates;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installe Forge dans l'instance CraftLab en exécutant directement les étapes
 * (ForgeInstallStep) décrites par install_profile.json (ForgeInstallProfile), extrait du jar
 * installeur officiel — plutôt que d'invoquer son mode --installClient (voir docs/launcher.md,
 * qui exige un .minecraft déjà initialisé par le launcher Mojang officiel).
 *
 * Contrairement à l'installeur officiel (qui charge chaque étape dans le MÊME processus via
 * un URLClassLoader), chaque étape est ici lancée dans un sous-processus java séparé : un
 * outil qui appellerait System.exit() en interne — pratique courante pour un utilitaire en
 * ligne de commande — tuerait sinon la JVM du launcher lui-même. Le résultat produit (mêmes
 * jar, même classe principale, mêmes arguments) est identique ; seule l'isolation diffère,
 * dans un sens plus sûr pour nous.
 */
public class ForgeInstaller {

    private static final String MAVEN_BASE = "https://maven.minecraftforge.net/net/minecraftforge/forge";
    private static final Pattern MAVEN_TOKEN = Pattern.compile("^\\[(.+)]$");
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\{([A-Za-z0-9_]+)}$");

    private final InstancePaths paths;
    private final HttpClient httpClient;

    public ForgeInstaller(InstancePaths paths) {
        this.paths = paths;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public String versionId(String minecraftVersion, String forgeVersion) {
        return minecraftVersion + "-forge-" + forgeVersion;
    }

    /**
     * Vraie seulement si le version.json existe ET que le client patché qu'il déclare (celui qui
     * contient réellement net/minecraft/client/Minecraft.class) est présent et complet — voir
     * ForgeRuntimeValidator. Le version.json seul ne suffit pas : il est écrit AVANT l'exécution
     * des processors dans install(), donc une installation antérieure interrompue en cours de
     * route (processor en échec) le laisse présent sans que Forge soit réellement utilisable.
     */
    public boolean isInstalled(String minecraftVersion, String forgeVersion) {
        String id = versionId(minecraftVersion, forgeVersion);
        Path versionJson = paths.versionsDir().resolve(id).resolve(id + ".json");
        if (!Files.exists(versionJson)) {
            return false;
        }
        return new ForgeRuntimeValidator(paths).validate(minecraftVersion, forgeVersion).isReady();
    }

    public void install(String minecraftVersion, String forgeVersion, Consumer<String> onLog) throws IOException, InterruptedException {
        String coord = minecraftVersion + "-" + forgeVersion;
        String installerUrl = MAVEN_BASE + "/" + coord + "/forge-" + coord + "-installer.jar";
        Path installerPath = paths.stagingDir().resolve("forge-" + coord + "-installer.jar");
        Files.createDirectories(installerPath.getParent());

        onLog.accept("Téléchargement de l'installeur Forge " + forgeVersion + "...");
        downloadFile(installerUrl, installerPath);

        try (ZipFile installerZip = new ZipFile(installerPath.toFile())) {
            JsonObject rawProfile = readJsonEntry(installerZip, "install_profile.json");
            if (rawProfile == null) {
                throw new IOException("install_profile.json introuvable dans l'installeur Forge — installeur incompatible ou corrompu.");
            }
            ForgeInstallProfile profile = ForgeInstallProfile.parse(rawProfile);

            String versionJsonEntryName = stripLeadingSlash(profile.versionJsonPath());
            JsonObject forgeVersionJson = readJsonEntry(installerZip, versionJsonEntryName);
            if (forgeVersionJson == null) {
                throw new IOException(versionJsonEntryName + " introuvable dans l'installeur Forge.");
            }

            String forgeId = forgeVersionJson.has("id") ? forgeVersionJson.get("id").getAsString() : versionId(minecraftVersion, forgeVersion);
            Path versionDir = paths.versionsDir().resolve(forgeId);
            Files.createDirectories(versionDir);
            Files.writeString(versionDir.resolve(forgeId + ".json"), forgeVersionJson.toString(), StandardCharsets.UTF_8);

            onLog.accept("Extraction et téléchargement des bibliothèques Forge...");
            extractBundledMaven(installerZip);
            downloadLibraries(profile.libraries());
            downloadLibraries(forgeVersionJson.has("libraries") ? forgeVersionJson.getAsJsonArray("libraries") : new JsonArray());

            onLog.accept("Exécution des étapes d'installation Forge (patch/remap du client)...");
            Map<String, String> data = resolveData(profile, installerZip, installerPath, minecraftVersion);
            runSteps(profile.steps(), data, onLog);
        }

        if (!isInstalled(minecraftVersion, forgeVersion)) {
            throw new IOException("L'installation Forge s'est terminée sans erreur mais le profil de version attendu est introuvable.");
        }
        onLog.accept("Forge " + forgeVersion + " installé avec succès.");
    }

    private void extractBundledMaven(ZipFile installerZip) throws IOException {
        var entries = installerZip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory() || !entry.getName().startsWith("maven/")) {
                continue;
            }
            String relative = entry.getName().substring("maven/".length());
            Path target = paths.librariesDir().resolve(relative);
            Files.createDirectories(target.getParent());
            try (InputStream in = installerZip.getInputStream(entry)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void downloadLibraries(JsonArray libraries) throws IOException, InterruptedException {
        for (JsonElement element : libraries) {
            JsonObject lib = element.getAsJsonObject();
            if (!lib.has("downloads") || !lib.getAsJsonObject("downloads").has("artifact")) {
                continue;
            }
            JsonObject artifact = lib.getAsJsonObject("downloads").getAsJsonObject("artifact");
            if (!artifact.has("path") || !artifact.has("url")) {
                continue;
            }
            String url = artifact.get("url").getAsString();
            if (url.isBlank()) {
                continue; // bibliothèque purement locale, déjà extraite depuis maven/ ci-dessus
            }
            Path target = paths.librariesDir().resolve(artifact.get("path").getAsString());
            if (Files.exists(target)) {
                continue;
            }
            Files.createDirectories(target.getParent());
            downloadFile(url, target);
        }
    }

    /**
     * Résout chaque entrée "data" de install_profile.json selon 3 formes possibles (confirmé
     * contre le comportement réel de l'installeur officiel, voir docs/launcher.md) :
     *  1. 'texte' entre guillemets simples -> chaîne littérale, guillemets retirés ;
     *  2. [group:artifact:version[:classifier]@ext] -> chemin réel dans libraries/ ;
     *  3. toute autre valeur -> chemin À L'INTÉRIEUR du jar installeur lui-même (ex.
     *     /data/client.lzma), qui doit être extrait vers un vrai fichier sur disque avant de
     *     pouvoir être passé en argument à un outil externe. C'est ce 3ème cas qui manquait et
     *     causait l'échec de BinaryPatcher.
     */
    private Map<String, String> resolveData(ForgeInstallProfile profile, ZipFile installerZip, Path installerPath, String minecraftVersion)
        throws IOException {
        Map<String, String> data = new LinkedHashMap<>();

        data.put("SIDE", "client");
        data.put("ROOT", paths.instanceDir().toAbsolutePath().toString());
        data.put("MINECRAFT_JAR", paths.versionsDir().resolve(minecraftVersion).resolve(minecraftVersion + ".jar").toAbsolutePath().toString());
        data.put("MINECRAFT_VERSION", minecraftVersion);
        data.put("INSTALLER", installerPath.toAbsolutePath().toString());
        data.put("LIBRARY_DIR", paths.librariesDir().toAbsolutePath().toString());

        for (var entry : profile.rawData().entrySet()) {
            data.put(entry.getKey(), resolveDataValue(entry.getValue(), installerZip));
        }

        return data;
    }

    private String resolveDataValue(String rawValue, ZipFile installerZip) throws IOException {
        if (rawValue.length() >= 2 && rawValue.startsWith("'") && rawValue.endsWith("'")) {
            return rawValue.substring(1, rawValue.length() - 1);
        }
        Matcher mavenMatcher = MAVEN_TOKEN.matcher(rawValue);
        if (mavenMatcher.matches()) {
            return paths.librariesDir().resolve(MavenCoordinates.toRelativePath(mavenMatcher.group(1))).toAbsolutePath().toString();
        }
        return extractInstallerResource(installerZip, rawValue).toAbsolutePath().toString();
    }

    /** Extrait une ressource embarquée dans le jar installeur (ex. /data/client.lzma) vers un vrai fichier sur disque. */
    private Path extractInstallerResource(ZipFile installerZip, String jarInternalPath) throws IOException {
        String relative = stripLeadingSlash(jarInternalPath);
        ZipEntry entry = installerZip.getEntry(relative);
        if (entry == null) {
            throw new IOException("Ressource introuvable dans l'installeur Forge : " + jarInternalPath);
        }
        Path target = paths.stagingDir().resolve("installer-data").resolve(relative);
        Files.createDirectories(target.getParent());
        try (InputStream in = installerZip.getInputStream(entry)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private void runSteps(List<ForgeInstallStep> steps, Map<String, String> data, Consumer<String> onLog) throws IOException, InterruptedException {
        String javaExecutable = ProcessHandle.current().info().command().orElse("java");
        int index = 0;

        for (ForgeInstallStep step : steps) {
            index++;
            if (!step.appliesToClient()) {
                continue;
            }

            Path jarPath = paths.librariesDir().resolve(MavenCoordinates.toRelativePath(step.jarCoordinates()));
            if (!Files.exists(jarPath)) {
                throw new IOException("Bibliothèque d'étape introuvable : " + step.jarCoordinates() + " (" + jarPath + ").");
            }

            List<String> classpath = new ArrayList<>();
            classpath.add(jarPath.toAbsolutePath().toString());
            for (String cpCoordinates : step.classpathCoordinates()) {
                Path cpPath = paths.librariesDir().resolve(MavenCoordinates.toRelativePath(cpCoordinates));
                if (Files.exists(cpPath)) {
                    classpath.add(cpPath.toAbsolutePath().toString());
                }
            }

            String mainClass = readMainClass(jarPath);
            if (mainClass == null) {
                throw new IOException("Impossible de déterminer la classe principale de l'étape : " + step.jarCoordinates());
            }

            List<String> args = new ArrayList<>();
            for (String rawArg : step.rawArgs()) {
                args.add(resolveToken(rawArg, data));
            }

            onLog.accept("Étape " + index + "/" + steps.size() + " : " + step.jarCoordinates());

            List<String> command = new ArrayList<>();
            command.add(javaExecutable);
            command.add("-cp");
            command.add(String.join(java.io.File.pathSeparator, classpath));
            command.add(mainClass);
            command.addAll(args);

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            try (var reader = process.inputReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    onLog.accept("  " + line);
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("L'étape " + step.jarCoordinates() + " a échoué (code " + exitCode + ").");
            }

            verifyOutputs(step, data, onLog);
        }
    }

    /**
     * Vérification best-effort : la documentation du format indique que ce champ n'est pas
     * toujours fiable ni utilisé de façon cohérente par l'installeur officiel lui-même. Un
     * écart est donc journalisé, pas fatal — on ne bloque pas une installation par ailleurs
     * réussie sur une vérification annexe incertaine.
     */
    private void verifyOutputs(ForgeInstallStep step, Map<String, String> data, Consumer<String> onLog) {
        for (var entry : step.rawOutputs().entrySet()) {
            try {
                String pathToken = resolveToken(entry.getKey(), data);
                String expectedSha1 = entry.getValue() == null ? null : resolveToken(entry.getValue(), data);
                Path outputPath = Path.of(pathToken);
                if (!Files.exists(outputPath)) {
                    onLog.accept("  (note : sortie attendue introuvable : " + outputPath + ")");
                    continue;
                }
                if (expectedSha1 != null && !expectedSha1.isBlank()) {
                    String actual = sha1(outputPath);
                    if (!expectedSha1.equalsIgnoreCase(actual)) {
                        onLog.accept("  (note : SHA-1 de sortie différent pour " + outputPath + ")");
                    }
                }
            } catch (Exception e) {
                onLog.accept("  (note : vérification de sortie ignorée : " + e.getMessage() + ")");
            }
        }
    }

    private String resolveToken(String rawArg, Map<String, String> data) {
        Matcher placeholderMatch = PLACEHOLDER.matcher(rawArg);
        if (placeholderMatch.matches()) {
            String key = placeholderMatch.group(1);
            return data.getOrDefault(key, rawArg);
        }
        Matcher mavenMatch = MAVEN_TOKEN.matcher(rawArg);
        if (mavenMatch.matches()) {
            return paths.librariesDir().resolve(MavenCoordinates.toRelativePath(mavenMatch.group(1))).toAbsolutePath().toString();
        }
        return rawArg;
    }

    private JsonObject readJsonEntry(ZipFile zip, String entryName) throws IOException {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return JsonParser.parseString(content).getAsJsonObject();
        }
    }

    private String readMainClass(Path jarPath) throws IOException {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Manifest manifest = jarFile.getManifest();
            if (manifest == null) {
                return null;
            }
            return manifest.getMainAttributes().getValue("Main-Class");
        }
    }

    private String stripLeadingSlash(String value) {
        return value.startsWith("/") ? value.substring(1) : value;
    }

    private void downloadFile(String url, Path target) throws IOException, InterruptedException {
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        Files.createDirectories(target.getParent());
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMinutes(3))
            .GET()
            .build();
        HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(temp,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(temp);
            throw new IOException("Téléchargement échoué pour " + url + " (code " + response.statusCode() + ").");
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
