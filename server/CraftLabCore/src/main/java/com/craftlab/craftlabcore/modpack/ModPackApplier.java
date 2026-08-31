package com.craftlab.craftlabcore.modpack;

import com.craftlab.craftlabcore.download.ChecksumVerifier;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Stream;

/**
 * Applique NEXT sur le vrai dossier /mods de Forge (backup -> staging -> validation -> swap ->
 * promotion), et détecte/répare une application interrompue au redémarrage suivant.
 *
 * Point de conception essentiel (voir aussi CraftLabCore#onServerStarting) : Forge scanne
 * /mods au tout début du démarrage de la JVM, avant que le code de CraftLabCore (chargé
 * DEPUIS /mods) ne puisse s'exécuter. Il est donc impossible d'influencer les mods chargés
 * pour le démarrage EN COURS. En revanche, modifier /mods pendant que le serveur tourne n'a
 * aucun effet sur l'instance déjà lancée (Forge ne rescane jamais /mods à chaud) : apply()
 * peut donc s'exécuter immédiatement, sans attendre l'arrêt du serveur. Le nouvel ensemble de
 * mods ne sera pris en compte qu'au PROCHAIN démarrage de la JVM, déclenché manuellement.
 */
public final class ModPackApplier {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String LOG_PREFIX = "[CraftLab] ";

    private static final ModPackApplier INSTANCE = new ModPackApplier();

    public static ModPackApplier get() {
        return INSTANCE;
    }

    private final ManagedModsStorage manifestStorage = new ManagedModsStorage();
    private final ModPackBackupManager backupManager = new ModPackBackupManager();
    private final ApplyStateStorage applyStateStorage = new ApplyStateStorage();

    private final ExecutorService executor;

    private ModPackApplier() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "CraftLabCore-ModPackApply");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newSingleThreadExecutor(threadFactory);
    }

    public CompletableFuture<ApplyResult> applyAsync() {
        return CompletableFuture.supplyAsync(this::apply, executor);
    }

    public CompletableFuture<ApplyResult> rollbackAsync() {
        return CompletableFuture.supplyAsync(this::rollback, executor);
    }

    /** Vérifie que tous les fichiers de NEXT existent et ont le bon SHA-256. Lecture seule. */
    public synchronized boolean verifyNext() {
        return verify(ModPackManager.get().getNext());
    }

    private boolean verify(ModPack pack) {
        Path downloadsRoot = FMLPaths.CONFIGDIR.get().resolve("craftlabcore").resolve("downloads");
        for (ModPackEntry entry : pack.getMods()) {
            Path jar = downloadsRoot.resolve(entry.getModId()).resolve(entry.getVersion()).resolve(entry.getAssetName());
            if (!Files.exists(jar)) {
                return false;
            }
            try {
                String actual = ChecksumVerifier.sha256(jar);
                if (entry.getSha256() == null || !entry.getSha256().equalsIgnoreCase(actual)) {
                    return false;
                }
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    /**
     * Applique NEXT sur le vrai /mods : backup, mise en zone de transit, validation, puis
     * remplacement effectif. Ne modifie CURRENT que si tout a réussi.
     */
    public synchronized ApplyResult apply() {
        LOGGER.info(LOG_PREFIX + "Loading ModPack...");

        ModPackManager manager = ModPackManager.get();
        ModPack current = manager.getCurrent();
        ModPack next = manager.getNext();

        LOGGER.info(LOG_PREFIX + "Current ModPack: v" + current.getGeneration());
        // NEXT n'a pas de compteur de génération qui lui soit propre (voir docs/modpack-lifecycle.md,
        // section "Génération") : la génération qu'il prendra à la promotion est toujours current+1.
        LOGGER.info(LOG_PREFIX + "Next ModPack: v" + (current.getGeneration() + 1));

        if (current.getMods().isEmpty() && next.getMods().isEmpty()) {
            return ApplyResult.alreadyUpToDate();
        }

        ModPackDiff diff = ModPackDiff.compute(current, next);
        LOGGER.info(LOG_PREFIX + "Changes detected: +" + diff.getAdded().size()
            + " ~" + diff.getUpdated().size() + " -" + diff.getRemoved().size());

        if (diff.isEmpty()) {
            next.setApplyState(ApplyState.APPLIED);
            manager.saveNextState();
            return ApplyResult.alreadyUpToDate();
        }

        LOGGER.info(LOG_PREFIX + "Validating files...");
        if (!verify(next)) {
            next.setApplyState(ApplyState.NOT_READY);
            manager.saveNextState();
            LOGGER.warn(LOG_PREFIX + "Failed to apply ModPack.");
            LOGGER.warn(LOG_PREFIX + "Previous ModPack has been preserved.");
            return ApplyResult.failure(ApplyResult.Status.VALIDATION_FAILED,
                "Un ou plusieurs fichiers du prochain ModPack sont manquants ou invalides. CURRENT n'a pas été modifié.");
        }

        LOGGER.info(LOG_PREFIX + "Applying ModPack...");
        ManagedModsManifest oldManifest = manifestStorage.load();

        try {
            backupManager.createBackup(oldManifest, currentModPackJsonPath());

            applyStateStorage.markApplying();
            stageAndSwap(oldManifest, next, diff);

            manager.promoteNextToCurrent();
            applyStateStorage.markApplied();

            LOGGER.info(LOG_PREFIX + "ModPack applied successfully.");
            return ApplyResult.success(diff);
        } catch (IOException e) {
            LOGGER.warn(LOG_PREFIX + "Failed to apply ModPack: " + e.getMessage());
            rollbackInternal();
            LOGGER.warn(LOG_PREFIX + "Previous ModPack has been preserved.");
            return ApplyResult.failure(ApplyResult.Status.APPLY_FAILED,
                "L'application a échoué et a été annulée : " + e.getMessage());
        }
    }

    private void stageAndSwap(ManagedModsManifest oldManifest, ModPack next, ModPackDiff diff) throws IOException {
        Path stagingRoot = FMLPaths.CONFIGDIR.get().resolve("craftlabcore").resolve("staging");
        Path modsDir = FMLPaths.MODSDIR.get();
        Path downloadsRoot = FMLPaths.CONFIGDIR.get().resolve("craftlabcore").resolve("downloads");

        cleanDirectory(stagingRoot);
        Files.createDirectories(stagingRoot);

        List<ModPackEntry> toDeploy = new ArrayList<>(diff.getAdded());
        diff.getUpdated().forEach(u -> toDeploy.add(u.to()));

        // 1. Copie et re-vérifie uniquement ce qui change réellement (jamais les inchangés).
        Map<String, Path> staged = new HashMap<>();
        for (ModPackEntry entry : toDeploy) {
            Path source = downloadsRoot.resolve(entry.getModId()).resolve(entry.getVersion()).resolve(entry.getAssetName());
            Path stagedFile = stagingRoot.resolve(entry.getAssetName());
            Files.copy(source, stagedFile, StandardCopyOption.REPLACE_EXISTING);

            String actual = ChecksumVerifier.sha256(stagedFile);
            if (!entry.getSha256().equalsIgnoreCase(actual)) {
                throw new IOException("Vérification post-copie échouée pour " + entry.getAssetName());
            }
            staged.put(entry.getModId(), stagedFile);
        }

        // 2. Retire de /mods les fichiers gérés qui ne doivent plus y être : REMOVE, et anciens
        //    noms de fichier des UPDATE (l'assetName peut changer entre versions).
        List<ModPackEntry> toRemoveFiles = new ArrayList<>(diff.getRemoved());
        diff.getUpdated().forEach(u -> toRemoveFiles.add(u.from()));
        for (ModPackEntry entry : toRemoveFiles) {
            Optional<ManagedModEntry> managed = oldManifest.find(entry.getModId());
            if (managed.isPresent()) {
                Files.deleteIfExists(modsDir.resolve(managed.get().getFile()));
            }
        }

        // 3. Déplace les fichiers réellement nouveaux/mis à jour vers le vrai /mods.
        for (ModPackEntry entry : toDeploy) {
            Path stagedFile = staged.get(entry.getModId());
            Path finalFile = modsDir.resolve(entry.getAssetName());
            ModFileReplacer.moveIntoMods(stagedFile, finalFile);
        }

        // 4. Reconstruit le manifest pour refléter exactement NEXT (inchangés compris).
        ManagedModsManifest newManifest = new ManagedModsManifest();
        for (ModPackEntry entry : next.getMods()) {
            newManifest.getMods().add(new ManagedModEntry(entry.getModId(), entry.getAssetName(), entry.getSha256()));
        }
        manifestStorage.save(newManifest);

        cleanDirectory(stagingRoot);
    }

    private void cleanDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream.toList();
            for (Path p : files) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** Restaure le dernier backup disponible. Ne redémarre rien : à appliquer au prochain redémarrage. */
    public synchronized ApplyResult rollback() {
        boolean ok = rollbackInternal();
        if (!ok) {
            return ApplyResult.failure(ApplyResult.Status.APPLY_FAILED, "Aucun backup disponible pour effectuer un rollback.");
        }
        return ApplyResult.success(null);
    }

    private boolean rollbackInternal() {
        Optional<ModPackBackup> backup = backupManager.findLatest();
        if (backup.isEmpty()) {
            applyStateStorage.markFailed();
            return false;
        }
        try {
            ManagedModsManifest currentManifest = manifestStorage.load();
            backupManager.restore(backup.get(), currentManifest);
            applyStateStorage.markFailed();
            LOGGER.warn(LOG_PREFIX + "Rolled back to backup " + backup.get().id() + ".");
            return true;
        } catch (IOException e) {
            LOGGER.error(LOG_PREFIX + "Rollback failed: " + e.getMessage());
            return false;
        }
    }

    /** À appeler au démarrage du serveur, avant toute autre logique ModPack. */
    public synchronized void checkForInterruptedApply() {
        if (applyStateStorage.load() == ApplyState.APPLYING) {
            LOGGER.warn(LOG_PREFIX + "Detected an interrupted ModPack application from a previous run.");
            boolean ok = rollbackInternal();
            if (ok) {
                LOGGER.warn(LOG_PREFIX + "Recovered to the last known-good ModPack state.");
            } else {
                LOGGER.error(LOG_PREFIX + "Could not automatically recover — manual inspection of "
                    + "config/craftlabcore/backups/ and config/craftlabcore/managed-mods.json is recommended.");
            }
        }
    }

    /** Si CURRENT est vide et NEXT ne l'est pas (premier démarrage avec un NEXT déjà préparé), tente un apply. */
    public synchronized void bootstrapIfNeeded() {
        ModPack current = ModPackManager.get().getCurrent();
        ModPack next = ModPackManager.get().getNext();
        if (!current.getMods().isEmpty() || next.getMods().isEmpty()) {
            return;
        }
        LOGGER.info(LOG_PREFIX + "No CURRENT ModPack found — attempting to bootstrap from NEXT...");
        ApplyResult result = apply();
        if (result.getStatus() != ApplyResult.Status.APPLIED) {
            LOGGER.warn(LOG_PREFIX + "Could not bootstrap from NEXT: " + result.getMessage());
        }
    }

    private Path currentModPackJsonPath() {
        return FMLPaths.CONFIGDIR.get().resolve("craftlabcore").resolve("modpack").resolve("current-modpack.json");
    }
}
