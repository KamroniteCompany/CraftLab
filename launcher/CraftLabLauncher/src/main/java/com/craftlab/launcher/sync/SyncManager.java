package com.craftlab.launcher.sync;

import com.craftlab.launcher.instance.InstalledModEntry;
import com.craftlab.launcher.instance.InstalledModPack;
import com.craftlab.launcher.instance.InstalledModPackStorage;
import com.craftlab.launcher.instance.InstancePaths;
import com.craftlab.launcher.modpack.ModPackProvider;
import com.craftlab.launcher.modpack.RemoteModEntry;
import com.craftlab.launcher.modpack.RemoteModPack;
import com.craftlab.launcher.state.LauncherState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Orchestre la synchronisation complète : récupération du ModPack distant (lecture seule —
 * ne modifie jamais rien côté serveur CraftLab), comparaison à l'installation locale,
 * téléchargement de ce qui manque, retrait de ce qui n'est plus requis, mise à jour de
 * l'état installé.
 */
public class SyncManager {

    public interface Listener {
        void onStateChanged(LauncherState state);

        void onModProgress(String modId, long bytesWritten, long totalBytesHint);

        void onLog(String message);
    }

    private final ModPackProvider provider;
    private final ModDownloader downloader;
    private final InstancePaths paths;
    private final InstalledModPackStorage installedStorage;

    public SyncManager(ModPackProvider provider, InstancePaths paths) {
        this.provider = provider;
        this.paths = paths;
        this.downloader = new ModDownloader(paths);
        this.installedStorage = new InstalledModPackStorage(paths);
    }

    public CompletableFuture<SyncPlan> sync(Listener listener) {
        listener.onStateChanged(LauncherState.CHECKING);
        listener.onLog("Récupération du ModPack officiel...");

        // provider.getCurrentModPack() ne doit normalement jamais lever d'exception de façon
        // synchrone (voir HttpModPackProvider), mais on s'en protège quand même ici : un throw
        // synchrone à cet endroit ferait perdre tout le chaînage .thenApply/.exceptionally
        // ci-dessous, et l'appelant (CraftLabLauncherApp.runSync) ne recevrait jamais aucune
        // notification d'erreur — c'est exactement ce qui provoquait le blocage silencieux.
        CompletableFuture<RemoteModPack> remoteFuture;
        try {
            remoteFuture = provider.getCurrentModPack();
        } catch (RuntimeException e) {
            remoteFuture = CompletableFuture.failedFuture(e);
        }

        return remoteFuture.thenApply(remote -> {
            listener.onLog("[CraftLab] ModPack récupéré.");

            InstalledModPack local = installedStorage.load();
            SyncPlan plan = ModPackComparator.compare(remote, local);

            listener.onLog("ModPack v" + remote.generation() + " — "
                + plan.toDownload().size() + " à télécharger, "
                + plan.toRemove().size() + " à retirer, "
                + plan.upToDate().size() + " déjà à jour.");

            if (plan.isEmpty()) {
                listener.onStateChanged(LauncherState.READY);
                listener.onLog("[CraftLab] Synchronisation terminée.");
                return plan;
            }

            listener.onStateChanged(LauncherState.DOWNLOADING);
            applyPlan(remote, plan, listener);

            listener.onStateChanged(LauncherState.READY);
            listener.onLog("[CraftLab] Synchronisation terminée.");
            return plan;
        }).exceptionally(throwable -> {
            listener.onStateChanged(LauncherState.ERROR);
            listener.onLog("Erreur de synchronisation : " + rootMessage(throwable));
            throw new CompletionException(throwable);
        });
    }

    private void applyPlan(RemoteModPack remote, SyncPlan plan, Listener listener) {
        try {
            Files.createDirectories(paths.modsDir());

            for (RemoteModEntry entry : plan.toDownload()) {
                if (downloader.isAlreadyValid(entry)) {
                    listener.onLog(entry.name() + " " + entry.version() + " : déjà en cache, pas de nouveau téléchargement.");
                } else {
                    listener.onLog("Téléchargement de " + entry.name() + " " + entry.version() + "...");
                    downloader.download(entry, bytes -> listener.onModProgress(entry.modId(), bytes, entry.size()));
                    listener.onLog(entry.name() + " " + entry.version() + " téléchargé et vérifié (SHA-256 OK).");
                }
            }

            // Réactive systématiquement chaque mod du ModPack distant dans mods/ (copie
            // idempotente depuis le cache, sans re-hash) : couvre aussi le cas où le fichier
            // actif aurait été supprimé manuellement alors que l'installation était par
            // ailleurs considérée à jour.
            for (RemoteModEntry entry : remote.mods()) {
                activateMod(entry);
            }

            for (InstalledModEntry removed : plan.toRemove()) {
                deactivateMod(removed);
                listener.onLog(removed.modId() + " n'est plus requis, retiré de l'installation active (fichier conservé en cache).");
            }

            List<InstalledModEntry> newEntries = new ArrayList<>();
            for (RemoteModEntry entry : remote.mods()) {
                newEntries.add(new InstalledModEntry(entry.modId(), entry.version(), entry.assetName(), entry.sha256(), entry.size()));
            }
            InstalledModPack newInstalled = new InstalledModPack(remote.minecraftVersion(), remote.forgeVersion(), remote.generation(), newEntries);
            installedStorage.save(newInstalled);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new SyncException("Échec de synchronisation : " + e.getMessage(), e);
        }
    }

    /** Copie le jar déjà validé du cache (downloads/) vers mods/, seulement s'il n'y est pas déjà. */
    private void activateMod(RemoteModEntry entry) throws IOException {
        Path cached = downloader.targetPath(entry);
        Path active = paths.modsDir().resolve(entry.assetName());
        if (!Files.exists(active) && Files.exists(cached)) {
            Files.copy(cached, active, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Retire uniquement le fichier actif dans mods/ ; le cache dans downloads/ est conservé. */
    private void deactivateMod(InstalledModEntry entry) throws IOException {
        Files.deleteIfExists(paths.modsDir().resolve(entry.assetName()));
    }

    private String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
