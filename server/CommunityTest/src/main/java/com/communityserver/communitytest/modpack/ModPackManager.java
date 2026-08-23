package com.communityserver.communitytest.modpack;

import com.communityserver.communitytest.download.DownloadResult;
import com.communityserver.communitytest.download.ModDownloadManager;
import com.communityserver.communitytest.mod.ModDefinition;
import com.communityserver.communitytest.mod.ModRegistry;
import com.communityserver.communitytest.mod.ModStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Gère la transition entre le ModPack CURRENT (ce avec quoi le serveur tourne actuellement,
 * chargé au démarrage et jamais modifié par cette classe) et NEXT (ce qui sera appliqué au
 * prochain démarrage — l'application NEXT -> CURRENT elle-même est une étape ultérieure, hors
 * périmètre ici). Ne charge, décharge ou n'exécute jamais un mod Forge : uniquement des
 * métadonnées et des fichiers JAR déjà validés sur disque.
 */
public final class ModPackManager {

    private static final ModPackManager INSTANCE = new ModPackManager();

    public static ModPackManager get() {
        return INSTANCE;
    }

    public enum PrepareResult { PREPARED, ALREADY_PREPARED, MOD_NOT_FOUND, NOT_ACCEPTED, NO_RELEASE_INFO, DOWNLOAD_FAILED }

    private final ModPackStorage storage = new ModPackStorage();
    private final ModDownloadManager downloadManager;

    private ModPack current;
    private ModPack next;

    private ModPackManager() {
        this.downloadManager = new ModDownloadManager();
    }

    /** À appeler une fois au démarrage du serveur, après DownloadConfig.load(). */
    public synchronized void load(String minecraftVersion, String forgeVersion) {
        ModPack loadedCurrent = storage.loadCurrent();
        ModPack loadedNext = storage.loadNext();

        current = loadedCurrent != null ? loadedCurrent : new ModPack(minecraftVersion, forgeVersion);
        next = loadedNext != null ? loadedNext : new ModPack(minecraftVersion, forgeVersion);

        if (loadedCurrent == null) {
            // Un CURRENT fraîchement créé (vide) est trivialement "déjà appliqué" : rien à déployer.
            current.setApplyState(ApplyState.APPLIED);
            storage.saveCurrent(current);
        }
        if (loadedNext == null) {
            storage.saveNext(next);
        }
    }

    public synchronized ModPack getCurrent() {
        return current;
    }

    public synchronized ModPack getNext() {
        return next;
    }

    public synchronized ModPackDiff diffCurrentVsNext() {
        return ModPackDiff.compute(current, next);
    }

    /** Prépare un mod ACCEPTED pour le prochain ModPack : télécharge et vérifie son JAR si besoin. */
    public CompletableFuture<PrepareResult> prepareMod(String modId) {
        Optional<ModDefinition> modOpt = ModRegistry.get().get(modId);
        if (modOpt.isEmpty()) {
            return CompletableFuture.completedFuture(PrepareResult.MOD_NOT_FOUND);
        }

        ModDefinition mod = modOpt.get();
        if (mod.getStatus() != ModStatus.ACCEPTED) {
            return CompletableFuture.completedFuture(PrepareResult.NOT_ACCEPTED);
        }
        if (mod.getRelease() == null || mod.getSource() == null) {
            return CompletableFuture.completedFuture(PrepareResult.NO_RELEASE_INFO);
        }

        synchronized (this) {
            next.setState(ModPackState.DOWNLOADING);
            storage.saveNext(next);
        }

        return downloadManager.ensureDownloaded(mod).thenApply(result -> applyDownloadResult(mod, result));
    }

    private synchronized PrepareResult applyDownloadResult(ModDefinition mod, DownloadResult result) {
        if (!result.isSuccess()) {
            next.setState(ModPackState.FAILED);
            storage.saveNext(next);
            return PrepareResult.DOWNLOAD_FAILED;
        }

        ModPackEntry entry = new ModPackEntry(
            mod.getId(),
            mod.getName(),
            mod.getVersion(),
            mod.getSource().getType(),
            mod.getRelease().getTag(),
            mod.getRelease().getReleaseId(),
            mod.getRelease().getAssetName(),
            result.getSha256(),
            result.getSizeBytes(),
            ModPackEntryStatus.READY
        );

        boolean alreadyIdentical = next.find(mod.getId())
            .map(existingEntry -> existingEntry.getVersion().equals(entry.getVersion())
                && existingEntry.getSha256().equals(entry.getSha256()))
            .orElse(false);

        next.upsert(entry);
        next.setState(ModPackState.READY);
        storage.saveNext(next);

        return alreadyIdentical ? PrepareResult.ALREADY_PREPARED : PrepareResult.PREPARED;
    }

    /** Retire un mod du NEXT ModPack. Ne touche jamais CURRENT ni les fichiers déjà téléchargés. */
    public synchronized boolean removeMod(String modId) {
        boolean removed = next.remove(modId);
        if (removed) {
            storage.saveNext(next);
        }
        return removed;
    }

    /** Persiste l'état courant de NEXT tel quel. Utilisé par ModPackApplier après une vérification. */
    public synchronized void saveNextState() {
        storage.saveNext(next);
    }

    /**
     * Remplace CURRENT par le contenu de NEXT (mêmes entrées), incrémente la génération.
     * Ne doit être appelé qu'après que ModPackApplier a réellement synchronisé /mods avec succès —
     * cette méthode ne fait que mettre à jour la source de vérité logique, jamais le disque.
     */
    public synchronized void promoteNextToCurrent() {
        ModPack promoted = new ModPack(next.getMinecraftVersion(), next.getForgeVersion());
        for (ModPackEntry entry : next.getMods()) {
            promoted.upsert(copyEntry(entry));
        }
        promoted.setState(ModPackState.READY);
        promoted.setApplyState(ApplyState.APPLIED);
        promoted.setGeneration(current.getGeneration() + 1);

        current = promoted;
        next.setApplyState(ApplyState.APPLIED);

        storage.saveCurrent(current);
        storage.saveNext(next);
    }

    private ModPackEntry copyEntry(ModPackEntry entry) {
        return new ModPackEntry(entry.getModId(), entry.getName(), entry.getVersion(), entry.getSource(),
            entry.getReleaseTag(), entry.getReleaseId(), entry.getAssetName(), entry.getSha256(),
            entry.getSize(), entry.getStatus());
    }

    /**
     * Compare le ModRegistry avec NEXT : prépare tout mod ACCEPTED manquant ou dont la version
     * a changé, et retire de NEXT tout mod qui n'est plus ACCEPTED. Retourne les modId traités.
     */
    public CompletableFuture<List<String>> sync() {
        List<ModDefinition> accepted = ModRegistry.get().getAll().stream()
            .filter(m -> m.getStatus() == ModStatus.ACCEPTED)
            .toList();

        List<String> processed = new ArrayList<>();

        List<String> toRemove;
        synchronized (this) {
            toRemove = next.getMods().stream()
                .map(ModPackEntry::getModId)
                .filter(modId -> accepted.stream().noneMatch(m -> m.getId().equals(modId)))
                .toList();
        }
        for (String modId : toRemove) {
            if (removeMod(modId)) {
                processed.add(modId);
            }
        }

        List<String> toPrepare;
        synchronized (this) {
            toPrepare = accepted.stream()
                .filter(m -> next.find(m.getId()).map(e -> !e.getVersion().equals(m.getVersion())).orElse(true))
                .map(ModDefinition::getId)
                .toList();
        }

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String modId : toPrepare) {
            chain = chain.thenCompose(v -> prepareMod(modId).thenAccept(r -> processed.add(modId)));
        }
        return chain.thenApply(v -> processed);
    }
}
