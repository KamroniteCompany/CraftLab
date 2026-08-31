package com.craftlab.launcher.sync;

import com.craftlab.launcher.instance.InstancePaths;
import com.craftlab.launcher.modpack.RemoteModEntry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.craftlab.launcher.sync.SyncTestSupport.sha256;
import static com.craftlab.launcher.sync.SyncTestSupport.writeFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * download() exige un downloadUrl https:// (validation de sécurité volontaire, jamais
 * contournée ici). fetchToTempFile() et finalizeDownload() sont les deux étapes qu'il orchestre,
 * extraites en méthodes package-private testables séparément : la première contre un vrai
 * serveur HTTP local (com.sun.net.httpserver, sans avoir besoin d'un certificat TLS), la seconde
 * sans réseau du tout. Ensemble, elles couvrent le vrai comportement réseau de download() sans
 * jamais affaiblir sa validation HTTPS-only.
 */
class ModDownloaderTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(byte[] body, int statusCode) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/mod.jar", exchange -> {
            exchange.sendResponseHeaders(statusCode, statusCode == 200 ? body.length : -1);
            if (statusCode == 200) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort() + "/mod.jar";
    }

    // ---- fetchToTempFile() : le vrai chemin HTTP, contre un serveur local réel ----

    @Test
    void fetchToTempFile_writesTheServerResponseBodyExactly(@TempDir Path tempDir) throws Exception {
        byte[] content = "real-mod-bytes".getBytes(StandardCharsets.UTF_8);
        String url = startServer(content, 200);
        ModDownloader downloader = new ModDownloader(InstancePaths.at(tempDir));
        Path tempPath = tempDir.resolve("mod.jar.part");

        downloader.fetchToTempFile(URI.create(url), tempPath, "blankmod", b -> {
        });

        assertEquals("real-mod-bytes", Files.readString(tempPath, StandardCharsets.UTF_8));
    }

    @Test
    void fetchToTempFile_serverError_throwsAndDeletesTempFile(@TempDir Path tempDir) throws Exception {
        String url = startServer(new byte[0], 500);
        ModDownloader downloader = new ModDownloader(InstancePaths.at(tempDir));
        Path tempPath = tempDir.resolve("mod.jar.part");

        IOException thrown = assertThrows(IOException.class,
            () -> downloader.fetchToTempFile(URI.create(url), tempPath, "blankmod", b -> {
            }));

        assertTrue(thrown.getMessage().contains("500"), "le message doit indiquer le code d'erreur réel : " + thrown.getMessage());
        assertFalse(Files.exists(tempPath), "aucun fichier .part ne doit subsister après un échec serveur");
    }

    @Test
    void fetchToTempFile_overwritesAStaleResidualPartFile(@TempDir Path tempDir) throws Exception {
        byte[] freshContent = "fresh-content-from-server".getBytes(StandardCharsets.UTF_8);
        String url = startServer(freshContent, 200);
        ModDownloader downloader = new ModDownloader(InstancePaths.at(tempDir));
        Path tempPath = tempDir.resolve("mod.jar.part");
        writeFile(tempPath, "garbage-left-over-from-an-earlier-interrupted-download-that-was-much-longer");

        downloader.fetchToTempFile(URI.create(url), tempPath, "blankmod", b -> {
        });

        assertEquals("fresh-content-from-server", Files.readString(tempPath, StandardCharsets.UTF_8),
            "le contenu résiduel doit être entièrement remplacé, pas concaténé");
    }

    // ---- finalizeDownload() : vérification SHA-256 et déplacement final, sans réseau ----

    @Test
    void finalizeDownload_correctSha_movesToFinalLocation(@TempDir Path tempDir) throws Exception {
        byte[] content = "verified-content".getBytes(StandardCharsets.UTF_8);
        Path tempPath = tempDir.resolve("mod.jar.part");
        writeFile(tempPath, content);
        Path finalPath = tempDir.resolve("mod.jar");
        ModDownloader downloader = new ModDownloader(InstancePaths.at(tempDir));

        downloader.finalizeDownload(tempPath, finalPath, sha256(content), "blankmod");

        assertTrue(Files.exists(finalPath));
        assertFalse(Files.exists(tempPath), "le fichier .part doit être déplacé, pas copié");
        assertEquals(sha256(content), sha256(finalPath));
    }

    @Test
    void finalizeDownload_wrongSha_throwsAndLeavesNoFileAnywhere(@TempDir Path tempDir) throws Exception {
        byte[] actuallyDownloaded = "tampered-or-wrong-mirror-content".getBytes(StandardCharsets.UTF_8);
        Path tempPath = tempDir.resolve("mod.jar.part");
        writeFile(tempPath, actuallyDownloaded);
        Path finalPath = tempDir.resolve("mod.jar");
        ModDownloader downloader = new ModDownloader(InstancePaths.at(tempDir));
        String wrongExpectedSha = "0".repeat(64);

        IOException thrown = assertThrows(IOException.class,
            () -> downloader.finalizeDownload(tempPath, finalPath, wrongExpectedSha, "blankmod"));

        assertTrue(thrown.getMessage().contains("SHA-256"));
        assertFalse(Files.exists(tempPath), "le fichier .part invalide ne doit jamais subsister");
        assertFalse(Files.exists(finalPath), "le fichier final ne doit jamais exister si le SHA est invalide");
    }

    // ---- isAlreadyValid()/matchesSha256() : décision cache valide vs invalide, sans réseau ----

    @Test
    void isAlreadyValid_true_whenCachedFileMatchesExpectedSha(@TempDir Path tempDir) throws Exception {
        ModDownloader downloader = new ModDownloader(InstancePaths.at(tempDir));
        byte[] content = "cached-content".getBytes(StandardCharsets.UTF_8);
        RemoteModEntry entry = new RemoteModEntry("blankmod", "BlankMod", "1.0.0", "blankmod-1.0.0.jar",
            sha256(content), 100L, "https://example.invalid/mod.jar");
        writeFile(downloader.targetPath(entry), content);

        assertTrue(downloader.isAlreadyValid(entry), "un cache dont le SHA-256 correspond exactement doit être réutilisé");
    }

    @Test
    void isAlreadyValid_false_whenCachedFileDoesNotMatchExpectedSha(@TempDir Path tempDir) throws Exception {
        ModDownloader downloader = new ModDownloader(InstancePaths.at(tempDir));
        RemoteModEntry entry = new RemoteModEntry("blankmod", "BlankMod", "1.0.0", "blankmod-1.0.0.jar",
            sha256("expected-content".getBytes(StandardCharsets.UTF_8)), 100L, "https://example.invalid/mod.jar");
        writeFile(downloader.targetPath(entry), "actually-different-or-corrupted-content");

        assertFalse(downloader.isAlreadyValid(entry), "un cache dont le contenu ne correspond plus au SHA-256 attendu doit être invalidé");
    }

    @Test
    void isAlreadyValid_false_whenFileDoesNotExistAtAll(@TempDir Path tempDir) {
        ModDownloader downloader = new ModDownloader(InstancePaths.at(tempDir));
        RemoteModEntry entry = new RemoteModEntry("blankmod", "BlankMod", "1.0.0", "blankmod-1.0.0.jar",
            "irrelevant", 100L, "https://example.invalid/mod.jar");

        assertFalse(downloader.isAlreadyValid(entry));
    }

    // ---- download() : la validation HTTPS-only elle-même, jamais contournée ----

    @Test
    void download_rejectsNonHttpsUrl_beforeAnyNetworkCall(@TempDir Path tempDir) {
        ModDownloader downloader = new ModDownloader(InstancePaths.at(tempDir));
        RemoteModEntry entry = new RemoteModEntry("blankmod", "BlankMod", "1.0.0", "blankmod-1.0.0.jar",
            "irrelevant", 100L, "http://example.invalid/mod.jar");

        IOException thrown = assertThrows(IOException.class, () -> downloader.download(entry, b -> {
        }));
        assertTrue(thrown.getMessage().contains("HTTPS"));
    }

    @Test
    void download_deletesAStaleResidualPartFile_beforeAttemptingTheNewDownload(@TempDir Path tempDir) throws Exception {
        ModDownloader downloader = new ModDownloader(InstancePaths.at(tempDir));
        RemoteModEntry entry = new RemoteModEntry("blankmod", "BlankMod", "1.0.0", "blankmod-1.0.0.jar",
            "irrelevant", 100L, "https://localhost:1/mod.jar"); // port 1 : connexion refusée, garanti injoignable

        Path stalePart = downloader.targetPath(entry).resolveSibling(downloader.targetPath(entry).getFileName() + ".part");
        writeFile(stalePart, "leftover-from-a-previous-interrupted-attempt");

        assertThrows(IOException.class, () -> downloader.download(entry, b -> {
        }), "la connexion doit échouer (port fermé), mais le nettoyage doit avoir eu lieu avant");

        assertFalse(Files.exists(stalePart),
            "download() supprime tout .part résiduel avant même de tenter la connexion réseau");
    }
}
