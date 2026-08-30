package com.craftlab.craftlabcore.download;

import com.craftlab.craftlabcore.mod.ModDefinition;
import com.craftlab.craftlabcore.mod.ModReleaseInfo;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;

/**
 * Télécharge et vérifie le JAR associé à un ModDefinition, de bout en bout et de façon
 * entièrement asynchrone (jamais le thread principal du serveur). Écrit toujours dans un
 * fichier temporaire ".part" et ne déplace vers l'emplacement final qu'après validation
 * complète (taille, SHA-256) — un fichier partiellement téléchargé ne peut donc jamais être
 * pris pour un fichier valide. Ne charge et n'exécute jamais le contenu du JAR.
 */
public final class ModDownloadManager {

    private static final String USER_AGENT = "CraftLabCore-Forge-Mod";
    private static final long DISK_SPACE_MARGIN_BYTES = 100L * 1024 * 1024; // marge de sécurité fixe : 100 Mo

    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final Semaphore semaphore;
    private final long maxBytes;
    private final Path downloadsRoot;

    public ModDownloadManager() {
        this.maxBytes = DownloadConfig.getMaxDownloadSizeBytes();
        this.semaphore = new Semaphore(DownloadConfig.getMaxConcurrentDownloads());

        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "CraftLabCore-ModDownload");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newFixedThreadPool(DownloadConfig.getMaxConcurrentDownloads() + 1, threadFactory);

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // NORMAL refuse nativement une redirection HTTPS -> HTTP : protège aussi les hops
            // de redirection (GitHub redirige souvent vers objects.githubusercontent.com).
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

        this.downloadsRoot = FMLPaths.CONFIGDIR.get().resolve("craftlabcore").resolve("downloads");
    }

    /**
     * S'assure que le JAR de ce mod est présent localement et valide. Ne télécharge à nouveau
     * que si le fichier local est absent ou que son SHA-256 ne correspond pas à celui attendu.
     * Toute la méthode s'exécute sur un pool de threads dédié, jamais sur l'appelant.
     */
    public CompletableFuture<DownloadResult> ensureDownloaded(ModDefinition mod) {
        ModReleaseInfo release = mod.getRelease();
        if (release == null || release.getAssetDownloadUrl() == null || release.getAssetName() == null) {
            return CompletableFuture.completedFuture(DownloadResult.failure(
                DownloadResult.Status.IO_ERROR, "Ce mod n'a pas d'information de release exploitable."));
        }

        Optional<String> urlError = DownloadValidator.validateUrl(release.getAssetDownloadUrl());
        if (urlError.isPresent()) {
            return CompletableFuture.completedFuture(DownloadResult.failure(DownloadResult.Status.REJECTED_INVALID_URL, urlError.get()));
        }
        Optional<String> nameError = DownloadValidator.validateAssetName(release.getAssetName());
        if (nameError.isPresent()) {
            return CompletableFuture.completedFuture(DownloadResult.failure(DownloadResult.Status.IO_ERROR, nameError.get()));
        }

        Path finalPath = targetPath(mod.getId(), mod.getVersion(), release.getAssetName());

        return CompletableFuture.supplyAsync(() -> {
            DownloadResult existing = checkExistingFile(finalPath, release.getExpectedSha256());
            if (existing != null) {
                return existing;
            }
            return acquireAndDownload(release, finalPath);
        }, executor);
    }

    private Path targetPath(String modId, String version, String assetName) {
        return downloadsRoot.resolve(modId).resolve(version).resolve(assetName);
    }

    /** Retourne un résultat SKIPPED si le fichier local est déjà valide, null s'il faut (re)télécharger. */
    private DownloadResult checkExistingFile(Path finalPath, String expectedSha256) {
        if (!Files.exists(finalPath)) {
            return null;
        }
        try {
            long size = Files.size(finalPath);
            if (size <= 0) {
                Files.deleteIfExists(finalPath);
                return null;
            }
            String actualSha256 = ChecksumVerifier.sha256(finalPath);
            if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(actualSha256)) {
                // Fichier local corrompu ou obsolète : on le supprime pour forcer un nouveau
                // téléchargement propre plutôt que de le "réparer" en place.
                Files.deleteIfExists(finalPath);
                return null;
            }
            return DownloadResult.skipped(finalPath, actualSha256, size);
        } catch (IOException e) {
            // En cas de doute sur un fichier illisible, on retélécharge plutôt que de lui faire confiance.
            return null;
        }
    }

    private DownloadResult acquireAndDownload(ModReleaseInfo release, Path finalPath) {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
        try {
            return performDownload(release, finalPath);
        } finally {
            semaphore.release();
        }
    }

    private DownloadResult performDownload(ModReleaseInfo release, Path finalPath) {
        Path tempPath = finalPath.resolveSibling(finalPath.getFileName() + ".part");

        try {
            Files.createDirectories(finalPath.getParent());
        } catch (IOException e) {
            return DownloadResult.failure(DownloadResult.Status.IO_ERROR,
                "Impossible de créer le dossier de téléchargement : " + e.getMessage());
        }

        if (!hasEnoughDiskSpace(finalPath.getParent())) {
            return DownloadResult.failure(DownloadResult.Status.INSUFFICIENT_DISK_SPACE,
                "Espace disque insuffisant pour télécharger ce mod en toute sécurité.");
        }

        cleanupTemp(tempPath); // nettoyage d'un .part résiduel d'une tentative interrompue précédente

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(release.getAssetDownloadUrl()))
            .timeout(Duration.ofMinutes(5))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();

        HttpResponse.BodyHandler<Path> handler = responseInfo -> {
            long contentLength = responseInfo.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > maxBytes) {
                // Content-Length annoncé déjà trop grand : on ne crée même pas de fichier.
                return HttpResponse.BodySubscribers.replacing(null);
            }
            return new SizeLimitedFileBodySubscriber(tempPath, maxBytes);
        };

        Path downloadedPath;
        try {
            HttpResponse<Path> response = httpClient.send(request, handler);
            if (response.statusCode() != 200) {
                cleanupTemp(tempPath);
                return DownloadResult.failure(DownloadResult.Status.NETWORK_ERROR,
                    "GitHub a répondu avec le code " + response.statusCode() + " (asset supprimé, release supprimée, ou rate limit).");
            }
            downloadedPath = response.body();
            if (downloadedPath == null) {
                cleanupTemp(tempPath);
                return DownloadResult.failure(DownloadResult.Status.REJECTED_TOO_LARGE,
                    "Le fichier annoncé dépasse la taille maximale autorisée.");
            }
        } catch (DownloadTooLargeException e) {
            cleanupTemp(tempPath);
            return DownloadResult.failure(DownloadResult.Status.REJECTED_TOO_LARGE, e.getMessage());
        } catch (HttpTimeoutException e) {
            cleanupTemp(tempPath);
            return DownloadResult.failure(DownloadResult.Status.NETWORK_ERROR, "Le téléchargement a expiré (timeout).");
        } catch (IOException e) {
            cleanupTemp(tempPath);
            return DownloadResult.failure(DownloadResult.Status.NETWORK_ERROR, "Erreur réseau : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cleanupTemp(tempPath);
            return DownloadResult.failure(DownloadResult.Status.NETWORK_ERROR, "Téléchargement interrompu.");
        }

        try {
            long size = Files.size(downloadedPath);
            if (size <= 0) {
                cleanupTemp(tempPath);
                return DownloadResult.failure(DownloadResult.Status.IO_ERROR, "Le fichier téléchargé est vide.");
            }

            String actualSha256 = ChecksumVerifier.sha256(downloadedPath);
            if (release.getExpectedSha256() != null && !release.getExpectedSha256().equalsIgnoreCase(actualSha256)) {
                cleanupTemp(tempPath);
                return DownloadResult.failure(DownloadResult.Status.HASH_MISMATCH,
                    "Le SHA-256 du fichier téléchargé ne correspond pas à celui annoncé.");
            }

            // Le déplacement vers l'emplacement final n'a lieu qu'ici, après validation complète :
            // avant ce point, seul un fichier ".part" existe, jamais confondu avec un JAR valide.
            Files.move(downloadedPath, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return DownloadResult.downloaded(finalPath, actualSha256, size);
        } catch (IOException e) {
            cleanupTemp(tempPath);
            return DownloadResult.failure(DownloadResult.Status.IO_ERROR, "Erreur lors de la validation du fichier : " + e.getMessage());
        }
    }

    private boolean hasEnoughDiskSpace(Path directory) {
        try {
            long usable = Files.getFileStore(directory).getUsableSpace();
            return usable > maxBytes + DISK_SPACE_MARGIN_BYTES;
        } catch (IOException e) {
            // Impossible de déterminer l'espace disque : on laisse la tentative se poursuivre
            // plutôt que de bloquer un téléchargement légitime sur une erreur de lecture du filesystem.
            return true;
        }
    }

    private void cleanupTemp(Path tempPath) {
        try {
            Files.deleteIfExists(tempPath);
        } catch (IOException ignored) {
            // Best-effort : un fichier .part résiduel sera nettoyé à la prochaine tentative.
        }
    }
}
