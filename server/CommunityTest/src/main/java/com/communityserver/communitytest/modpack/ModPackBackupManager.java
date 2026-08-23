package com.communityserver.communitytest.modpack;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Crée et restaure des instantanés du dossier /mods réel de Forge, limités aux fichiers gérés
 * par CraftLab (d'après le manifest). Ne touche jamais aux fichiers non gérés (JEI, plugins
 * serveur tiers, etc.), que ce soit à la sauvegarde ou à la restauration.
 */
public class ModPackBackupManager {

    private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path backupsRoot;
    private final ManagedModsStorage manifestStorage;

    public ModPackBackupManager() {
        this.backupsRoot = FMLPaths.CONFIGDIR.get().resolve("communitytest").resolve("backups");
        this.manifestStorage = new ManagedModsStorage();
    }

    /** Sauvegarde les fichiers actuellement gérés (d'après ce manifest) + le manifest + current-modpack.json. */
    public ModPackBackup createBackup(ManagedModsManifest currentManifest, Path currentModPackJson) throws IOException {
        String id = ID_FORMAT.format(Instant.now().atZone(ZoneOffset.UTC));
        Path folder = backupsRoot.resolve(id);
        Files.createDirectories(folder.resolve("mods"));

        Path modsDir = FMLPaths.MODSDIR.get();
        for (ManagedModEntry entry : currentManifest.getMods()) {
            Path source = modsDir.resolve(entry.getFile());
            if (Files.exists(source)) {
                Files.copy(source, folder.resolve("mods").resolve(entry.getFile()), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        manifestStorage.save(currentManifest, folder.resolve("managed-mods.json"));
        if (Files.exists(currentModPackJson)) {
            Files.copy(currentModPackJson, folder.resolve("current-modpack.json"), StandardCopyOption.REPLACE_EXISTING);
        }

        return new ModPackBackup(id, Instant.now(), folder);
    }

    public Optional<ModPackBackup> findLatest() {
        if (!Files.isDirectory(backupsRoot)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.list(backupsRoot)) {
            return stream.filter(Files::isDirectory)
                .max(Comparator.comparing(p -> p.getFileName().toString()))
                .map(p -> new ModPackBackup(p.getFileName().toString(), Instant.EPOCH, p));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Restaure /mods et le manifest depuis ce backup. manifestBeforeRestore doit refléter l'état
     * du manifest AU MOMENT de l'appel (pas forcément celui du backup lui-même, qui peut être
     * plus ancien) : tout fichier géré listé ici mais absent du backup est retiré, avant que les
     * fichiers du backup ne soient recopiés.
     */
    public void restore(ModPackBackup backup, ManagedModsManifest manifestBeforeRestore) throws IOException {
        Path modsDir = FMLPaths.MODSDIR.get();
        Path backedUpModsDir = backup.folder().resolve("mods");

        for (ManagedModEntry entry : manifestBeforeRestore.getMods()) {
            Path backedUpFile = backedUpModsDir.resolve(entry.getFile());
            if (!Files.exists(backedUpFile)) {
                Files.deleteIfExists(modsDir.resolve(entry.getFile()));
            }
        }

        if (Files.isDirectory(backedUpModsDir)) {
            try (Stream<Path> stream = Files.list(backedUpModsDir)) {
                List<Path> files = stream.toList();
                for (Path file : files) {
                    Files.copy(file, modsDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        Path manifestBackup = backup.folder().resolve("managed-mods.json");
        if (Files.exists(manifestBackup)) {
            Files.createDirectories(manifestStorage.path().getParent());
            Files.copy(manifestBackup, manifestStorage.path(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
