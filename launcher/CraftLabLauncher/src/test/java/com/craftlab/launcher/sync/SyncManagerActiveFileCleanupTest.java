package com.craftlab.launcher.sync;

import com.craftlab.launcher.instance.InstalledModEntry;
import com.craftlab.launcher.instance.InstalledModPack;
import com.craftlab.launcher.instance.InstalledModPackStorage;
import com.craftlab.launcher.instance.InstancePaths;
import com.craftlab.launcher.modpack.ModPackProvider;
import com.craftlab.launcher.modpack.RemoteModEntry;
import com.craftlab.launcher.modpack.RemoteModPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.craftlab.launcher.sync.SyncTestSupport.await;
import static com.craftlab.launcher.sync.SyncTestSupport.noopListener;
import static com.craftlab.launcher.sync.SyncTestSupport.sha256;
import static com.craftlab.launcher.sync.SyncTestSupport.writeFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduit un bug réel trouvé en testant le cycle complet de mise à jour d'un mod
 * (CraftLabTest/blankmod 1.0.0 -> 1.1.0, voir docs) : avant correction, SyncManager copiait le
 * JAR de la nouvelle version dans mods/ sans jamais retirer l'ancien JAR du même modId (son
 * assetName ayant changé, il n'apparaissait ni dans remote.mods() ni dans plan.toRemove()) — les
 * deux JAR coexistaient, ce que Forge refuse (modId en double).
 */
class SyncManagerActiveFileCleanupTest {

    @Test
    void updatingAModVersionRemovesThePreviousActiveJar(@TempDir Path tempDir) throws Exception {
        InstancePaths paths = InstancePaths.at(tempDir);
        Files.createDirectories(paths.modsDir());

        byte[] oldBytes = "old-blankmod-1.0.0".getBytes(StandardCharsets.UTF_8);
        byte[] newBytes = "new-blankmod-1.1.0".getBytes(StandardCharsets.UTF_8);
        String oldSha256 = sha256(oldBytes);
        String newSha256 = sha256(newBytes);

        // Cache déjà présent pour les deux versions (comme après un premier téléchargement réel).
        writeFile(paths.downloadsDir().resolve("blankmod").resolve("1.0.0").resolve("blankmod-1.0.0.jar"), oldBytes);
        writeFile(paths.downloadsDir().resolve("blankmod").resolve("1.1.0").resolve("blankmod-1.1.0.jar"), newBytes);

        // Installation active actuelle : blankmod-1.0.0.jar, comme après un premier `sync()`.
        writeFile(paths.modsDir().resolve("blankmod-1.0.0.jar"), oldBytes);
        new InstalledModPackStorage(paths).save(new InstalledModPack("1.21.1", "52.1.0", 1, List.of(
            new InstalledModEntry("blankmod", "1.0.0", "blankmod-1.0.0.jar", oldSha256, oldBytes.length)
        )));

        // Le ModPack distant annonce maintenant la 1.1.0 (nouveau assetName), exactement comme
        // current-modpack-launcher.json après une promotion NEXT -> CURRENT réelle.
        RemoteModPack remote = new RemoteModPack("1.21.1", "52.1.0", 2, List.of(
            new RemoteModEntry("blankmod", "BlankMod", "1.1.0", "blankmod-1.1.0.jar", newSha256, newBytes.length,
                "https://example.invalid/blankmod-1.1.0.jar")
        ));
        ModPackProvider provider = () -> CompletableFuture.completedFuture(remote);

        SyncManager syncManager = new SyncManager(provider, paths);
        SyncPlan plan = await(syncManager.sync(noopListener()));

        assertEquals(1, plan.toDownload().size(), "la 1.1.0 doit être détectée comme mise à jour à appliquer");

        assertTrue(Files.exists(paths.modsDir().resolve("blankmod-1.1.0.jar")),
            "la nouvelle version doit être active");
        assertFalse(Files.exists(paths.modsDir().resolve("blankmod-1.0.0.jar")),
            "l'ancien JAR du même modId ne doit plus être présent dans mods/ (sinon Forge refuse un modId en double)");

        // Le cache des deux versions doit rester intact (jamais purgé automatiquement).
        assertTrue(Files.exists(paths.downloadsDir().resolve("blankmod").resolve("1.0.0").resolve("blankmod-1.0.0.jar")));
        assertTrue(Files.exists(paths.downloadsDir().resolve("blankmod").resolve("1.1.0").resolve("blankmod-1.1.0.jar")));
    }
}
