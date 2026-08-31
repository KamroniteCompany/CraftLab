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

/**
 * Reproduit un second bug réel trouvé en testant le cycle de mise à jour : ModPackComparator ne
 * compare que les métadonnées enregistrées (installed-modpack.json), jamais le contenu réel du
 * fichier actif. Un JAR actif corrompu ou modifié manuellement — alors que le cache et le
 * manifeste restent corrects — était donc classé "à jour" indéfiniment et jamais réparé.
 */
class SyncManagerCorruptedActiveFileTest {

    @Test
    void aCorruptedActiveJarIsRedetectedAndRepairedFromCache(@TempDir Path tempDir) throws Exception {
        InstancePaths paths = InstancePaths.at(tempDir);
        Files.createDirectories(paths.modsDir());

        byte[] validBytes = "valid-blankmod-1.1.0".getBytes(StandardCharsets.UTF_8);
        String validSha256 = sha256(validBytes);

        // Cache valide (jamais touché par la corruption).
        writeFile(paths.downloadsDir().resolve("blankmod").resolve("1.1.0").resolve("blankmod-1.1.0.jar"), validBytes);

        // Fichier actif corrompu, alors que le manifeste local dit encore que tout est correct
        // (exactement le scénario : "seul le fichier actif a été modifié").
        byte[] corruptedBytes = "valid-blankmod-1.1.0-TAMPERED".getBytes(StandardCharsets.UTF_8);
        writeFile(paths.modsDir().resolve("blankmod-1.1.0.jar"), corruptedBytes);
        new InstalledModPackStorage(paths).save(new InstalledModPack("1.21.1", "52.1.0", 1, List.of(
            new InstalledModEntry("blankmod", "1.1.0", "blankmod-1.1.0.jar", validSha256, validBytes.length)
        )));

        RemoteModPack remote = new RemoteModPack("1.21.1", "52.1.0", 1, List.of(
            new RemoteModEntry("blankmod", "BlankMod", "1.1.0", "blankmod-1.1.0.jar", validSha256, validBytes.length,
                "https://example.invalid/blankmod-1.1.0.jar")
        ));
        ModPackProvider provider = () -> CompletableFuture.completedFuture(remote);

        SyncManager syncManager = new SyncManager(provider, paths);
        SyncPlan plan = await(syncManager.sync(noopListener()));

        assertEquals(1, plan.toDownload().size(),
            "un fichier actif dont le SHA-256 réel ne correspond plus doit être re-détecté, pas classé 'à jour'");

        Path repaired = paths.modsDir().resolve("blankmod-1.1.0.jar");
        assertEquals(validSha256, sha256(repaired), "le fichier actif doit être réparé depuis le cache valide");
    }
}
