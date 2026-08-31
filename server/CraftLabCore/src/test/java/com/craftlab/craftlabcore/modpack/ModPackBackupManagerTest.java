package com.craftlab.craftlabcore.modpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Couvre le mécanisme réel de rollback : createBackup() (instantané avant une application
 * risquée) et restore() (utilisé par ModPackApplier.rollbackInternal(), lui-même appelé après un
 * échec d'apply() ou par checkForInterruptedApply() au redémarrage suivant un crash). Teste
 * directement ces deux méthodes avec des racines de fichiers arbitraires (constructeur de test) :
 * ModPackApplier lui-même reste hors de portée d'un test JUnit (dépend de ModRegistry/FMLPaths,
 * voir sa javadoc), mais la mécanique de sauvegarde/restauration qu'il orchestre est ici
 * entièrement vérifiée.
 */
class ModPackBackupManagerTest {

    @Test
    void createBackup_copiesManagedFilesManifestAndCurrentModPackJson(@TempDir Path dir) throws Exception {
        Path modsDir = dir.resolve("mods");
        Files.createDirectories(modsDir);
        Files.writeString(modsDir.resolve("craftlabcore-1.0.1.jar"), "core-content", StandardCharsets.UTF_8);
        Files.writeString(modsDir.resolve("jei-1.0.0.jar"), "unmanaged-mod", StandardCharsets.UTF_8); // jamais géré, jamais sauvegardé

        Path currentModPackJson = dir.resolve("current-modpack.json");
        Files.writeString(currentModPackJson, "{\"generation\":3}", StandardCharsets.UTF_8);

        ManagedModsStorage manifestStorage = new ManagedModsStorage(dir.resolve("managed-mods.json"));
        ModPackBackupManager backupManager = new ModPackBackupManager(dir.resolve("backups"), modsDir, manifestStorage);

        ManagedModsManifest manifest = new ManagedModsManifest();
        manifest.getMods().add(new ManagedModEntry("craftlabcore", "craftlabcore-1.0.1.jar", "sha-core"));

        ModPackBackup backup = backupManager.createBackup(manifest, currentModPackJson);

        assertTrue(Files.exists(backup.folder().resolve("mods").resolve("craftlabcore-1.0.1.jar")));
        assertFalse(Files.exists(backup.folder().resolve("mods").resolve("jei-1.0.0.jar")),
            "un fichier non géré par CraftLab ne doit jamais être inclus dans le backup");
        assertTrue(Files.exists(backup.folder().resolve("managed-mods.json")));
        assertTrue(Files.exists(backup.folder().resolve("current-modpack.json")));
    }

    @Test
    void findLatest_returnsTheMostRecentBackupById(@TempDir Path dir) throws Exception {
        Path backupsRoot = dir.resolve("backups");
        Files.createDirectories(backupsRoot.resolve("20260101-100000"));
        Files.createDirectories(backupsRoot.resolve("20260831-215500")); // le plus récent
        Files.createDirectories(backupsRoot.resolve("20260615-120000"));

        ModPackBackupManager backupManager = new ModPackBackupManager(
            backupsRoot, dir.resolve("mods"), new ManagedModsStorage(dir.resolve("managed-mods.json")));

        Optional<ModPackBackup> latest = backupManager.findLatest();

        assertTrue(latest.isPresent());
        assertEquals("20260831-215500", latest.get().id());
    }

    @Test
    void findLatest_withNoBackups_isEmpty(@TempDir Path dir) {
        ModPackBackupManager backupManager = new ModPackBackupManager(
            dir.resolve("backups"), dir.resolve("mods"), new ManagedModsStorage(dir.resolve("managed-mods.json")));

        assertTrue(backupManager.findLatest().isEmpty());
    }

    @Test
    void restore_putsBackedUpFilesBackAndRemovesModsAddedSinceTheBackup(@TempDir Path dir) throws Exception {
        Path modsDir = dir.resolve("mods");
        Files.createDirectories(modsDir);
        ManagedModsStorage manifestStorage = new ManagedModsStorage(dir.resolve("managed-mods.json"));
        ModPackBackupManager backupManager = new ModPackBackupManager(dir.resolve("backups"), modsDir, manifestStorage);

        // Backup pris quand seul craftlabcore était géré.
        ManagedModsManifest manifestAtBackupTime = new ManagedModsManifest();
        manifestAtBackupTime.getMods().add(new ManagedModEntry("craftlabcore", "craftlabcore-1.0.0.jar", "sha-old"));
        Files.writeString(modsDir.resolve("craftlabcore-1.0.0.jar"), "core-1.0.0", StandardCharsets.UTF_8);
        ModPackBackup backup = backupManager.createBackup(manifestAtBackupTime, dir.resolve("current-modpack.json"));

        // Depuis, une application a mis à jour craftlabcore ET ajouté blankmod (ce qu'on veut annuler).
        Files.writeString(modsDir.resolve("craftlabcore-1.0.0.jar"), "core-1.0.1-CORRUPTED-OR-WRONG", StandardCharsets.UTF_8);
        Files.writeString(modsDir.resolve("blankmod-1.0.0.jar"), "blankmod-content", StandardCharsets.UTF_8);
        ManagedModsManifest manifestBeforeRestore = new ManagedModsManifest();
        manifestBeforeRestore.getMods().add(new ManagedModEntry("craftlabcore", "craftlabcore-1.0.0.jar", "sha-new"));
        manifestBeforeRestore.getMods().add(new ManagedModEntry("blankmod", "blankmod-1.0.0.jar", "sha-blank"));

        backupManager.restore(backup, manifestBeforeRestore);

        assertEquals("core-1.0.0", Files.readString(modsDir.resolve("craftlabcore-1.0.0.jar"), StandardCharsets.UTF_8),
            "le contenu géré doit revenir exactement à l'état du backup");
        assertFalse(Files.exists(modsDir.resolve("blankmod-1.0.0.jar")),
            "un mod géré ajouté après le backup et absent du backup doit être retiré par le rollback");
    }
}
