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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.craftlab.launcher.sync.SyncTestSupport.await;
import static com.craftlab.launcher.sync.SyncTestSupport.noopListener;
import static com.craftlab.launcher.sync.SyncTestSupport.sha256;
import static com.craftlab.launcher.sync.SyncTestSupport.writeFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le manifeste local (installed-modpack.json) peut affirmer qu'un mod est installé alors que le
 * fichier actif dans mods/ a disparu (suppression manuelle, antivirus, etc.). Sans une
 * vérification du disque réel (voir SyncManager.reclassifyCorruptedActiveFiles()), ce cas serait
 * indiscernable d'un mod réellement à jour et ne serait jamais réparé.
 */
class SyncManagerMissingActiveFileTest {

    @Test
    void missingActiveJar_isRestoredFromCache_withoutAnyNetworkDownload(@TempDir Path tempDir) throws Exception {
        InstancePaths paths = InstancePaths.at(tempDir);
        Files.createDirectories(paths.modsDir());

        byte[] content = "blankmod-1.0.0-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = sha256(content);

        // Cache valide déjà présent (comme après un premier téléchargement réel).
        writeFile(paths.downloadsDir().resolve("blankmod").resolve("1.0.0").resolve("blankmod-1.0.0.jar"), content);

        // Le manifeste dit "installé", mais le fichier actif n'existe PAS (supprimé manuellement).
        new InstalledModPackStorage(paths).save(new InstalledModPack("1.21.1", "52.1.0", 1, List.of(
            new InstalledModEntry("blankmod", "1.0.0", "blankmod-1.0.0.jar", sha, content.length)
        )));
        assertTrue(!Files.exists(paths.modsDir().resolve("blankmod-1.0.0.jar")), "précondition : le fichier actif est bien absent");

        // downloadUrl volontairement invalide (http://, jamais https://) : si SyncManager tentait
        // un vrai téléchargement réseau au lieu de réutiliser le cache, ce test échouerait avec
        // une erreur de synchronisation au lieu de réussir.
        RemoteModPack remote = new RemoteModPack("1.21.1", "52.1.0", 1, List.of(
            new RemoteModEntry("blankmod", "BlankMod", "1.0.0", "blankmod-1.0.0.jar", sha, content.length,
                "http://this-would-fail-if-actually-called.invalid/blankmod-1.0.0.jar")
        ));
        ModPackProvider provider = () -> CompletableFuture.completedFuture(remote);

        SyncManager syncManager = new SyncManager(provider, paths);
        SyncPlan plan = await(syncManager.sync(noopListener()));

        assertEquals(1, plan.toDownload().size(),
            "un fichier actif absent doit être re-détecté, pas classé 'à jour' sur la seule foi des métadonnées");
        assertTrue(Files.exists(paths.modsDir().resolve("blankmod-1.0.0.jar")),
            "le fichier doit avoir été restauré depuis le cache");
        assertEquals(sha, sha256(paths.modsDir().resolve("blankmod-1.0.0.jar")));
    }
}
