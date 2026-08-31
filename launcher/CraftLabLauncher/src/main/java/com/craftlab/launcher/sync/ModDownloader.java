package com.craftlab.launcher.sync;

import com.craftlab.launcher.instance.InstancePaths;
import com.craftlab.launcher.modpack.RemoteModEntry;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.LongConsumer;

/**
 * Télécharge et vérifie le JAR d'un mod avec la même stratégie de sécurité que le serveur
 * CraftLab : HTTPS uniquement, fichier .part temporaire, taille bornée pendant le flux (pas
 * seulement vérifiée après coup), SHA-256 vérifié avant tout déplacement vers l'emplacement
 * final. Les fichiers sont mis en cache par modId/version dans downloads/, jamais supprimés
 * automatiquement — un mod retiré puis réintroduit ne redéclenche pas de téléchargement si
 * le fichier en cache est toujours valide.
 */
public class ModDownloader {

    private static final long MAX_MOD_SIZE_BYTES = 200L * 1024 * 1024;

    private final HttpClient httpClient;
    private final InstancePaths paths;

    public ModDownloader(InstancePaths paths) {
        this.paths = paths;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public Path targetPath(RemoteModEntry entry) {
        return paths.downloadsDir().resolve(entry.modId()).resolve(entry.version()).resolve(entry.assetName());
    }

    /** Vérifie le cache local, sans réseau : true si le fichier existe déjà avec le bon SHA-256. */
    public boolean isAlreadyValid(RemoteModEntry entry) {
        return matchesSha256(targetPath(entry), entry.sha256());
    }

    /** Vérifie qu'un fichier quelconque (cache ou installation active) correspond exactement au SHA-256 attendu. */
    public boolean matchesSha256(Path file, String expectedSha256) {
        if (!Files.exists(file)) {
            return false;
        }
        try {
            return expectedSha256 != null && expectedSha256.equalsIgnoreCase(sha256(file));
        } catch (IOException e) {
            return false;
        }
    }

    public void download(RemoteModEntry entry, LongConsumer onProgress) throws IOException, InterruptedException {
        if (entry.downloadUrl() == null || !entry.downloadUrl().toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IOException("URL de téléchargement invalide ou non HTTPS pour " + entry.modId() + ".");
        }

        Path finalPath = targetPath(entry);
        Path tempPath = finalPath.resolveSibling(finalPath.getFileName() + ".part");
        Files.createDirectories(finalPath.getParent());
        Files.deleteIfExists(tempPath);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(entry.downloadUrl()))
            .timeout(Duration.ofMinutes(5))
            .GET()
            .build();

        HttpResponse.BodyHandler<Path> handler = responseInfo -> {
            long contentLength = responseInfo.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > MAX_MOD_SIZE_BYTES) {
                return HttpResponse.BodySubscribers.replacing(null);
            }
            return new ProgressLimitedBodySubscriber(tempPath, MAX_MOD_SIZE_BYTES, onProgress);
        };

        HttpResponse<Path> response = httpClient.send(request, handler);
        if (response.statusCode() != 200 || response.body() == null) {
            Files.deleteIfExists(tempPath);
            throw new IOException("Échec du téléchargement de " + entry.modId() + " (code " + response.statusCode() + ").");
        }

        String actual = sha256(response.body());
        if (entry.sha256() == null || !entry.sha256().equalsIgnoreCase(actual)) {
            Files.deleteIfExists(tempPath);
            throw new IOException("SHA-256 invalide pour " + entry.modId() + " après téléchargement.");
        }

        Files.move(response.body(), finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 non disponible sur cette JVM.", e);
        }
    }
}
