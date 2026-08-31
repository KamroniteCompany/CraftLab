package com.craftlab.craftlabcore.modpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * managed-mods.json est le manifeste qui décide quels fichiers de /mods sont sous la
 * responsabilité de CraftLab (voir ManagedModsManifest) : il doit survivre à une écriture puis
 * lecture identique, et ne jamais planter sur un fichier absent ou corrompu (le pire cas
 * acceptable est un manifeste vide, jamais une exception qui bloquerait apply()/rollback()).
 */
class ManagedModsStorageTest {

    @Test
    void saveThenLoad_roundTripsAllFields(@TempDir Path dir) {
        Path file = dir.resolve("managed-mods.json");
        ManagedModsStorage storage = new ManagedModsStorage(file);

        ManagedModsManifest manifest = new ManagedModsManifest();
        manifest.getMods().add(new ManagedModEntry("craftlabcore", "craftlabcore-1.0.1.jar", "abc123"));
        manifest.getMods().add(new ManagedModEntry("blankmod", "blankmod-1.0.0.jar", "def456"));
        storage.save(manifest);

        ManagedModsManifest reloaded = storage.load();

        assertEquals(2, reloaded.getMods().size());
        assertTrue(reloaded.find("craftlabcore").isPresent());
        assertEquals("craftlabcore-1.0.1.jar", reloaded.find("craftlabcore").get().getFile());
        assertEquals("abc123", reloaded.find("craftlabcore").get().getSha256());
        assertTrue(reloaded.isManagedFile("blankmod-1.0.0.jar"));
        assertTrue(!reloaded.isManagedFile("jei-1.0.0.jar"), "un fichier non géré ne doit jamais apparaître comme géré");
    }

    @Test
    void missingFile_loadsAsEmptyManifest(@TempDir Path dir) {
        ManagedModsStorage storage = new ManagedModsStorage(dir.resolve("does-not-exist.json"));

        ManagedModsManifest manifest = storage.load();

        assertTrue(manifest.getMods().isEmpty());
    }

    @Test
    void corruptedJson_loadsAsEmptyManifestInsteadOfThrowing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("managed-mods.json");
        Files.writeString(file, "{ this is not valid json", StandardCharsets.UTF_8);
        ManagedModsStorage storage = new ManagedModsStorage(file);

        ManagedModsManifest manifest = storage.load();

        assertTrue(manifest.getMods().isEmpty(),
            "un manifeste corrompu ne doit jamais faire planter le chargement (apply()/rollback() en dépendent)");
    }

    @Test
    void explicitPathOverload_writesToArbitraryLocation_usedForBackupCopies(@TempDir Path dir) {
        ManagedModsStorage storage = new ManagedModsStorage(dir.resolve("real-location.json"));
        Path backupCopy = dir.resolve("backups").resolve("20260101-000000").resolve("managed-mods.json");

        ManagedModsManifest manifest = new ManagedModsManifest();
        manifest.getMods().add(new ManagedModEntry("craftlabcore", "craftlabcore-1.0.1.jar", "abc123"));
        storage.save(manifest, backupCopy);

        assertTrue(Files.exists(backupCopy));
        ManagedModsManifest reloaded = storage.load(backupCopy);
        assertEquals(1, reloaded.getMods().size());
    }
}
