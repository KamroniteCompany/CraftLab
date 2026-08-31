package com.craftlab.craftlabcore.github;

import com.craftlab.craftlabcore.modpack.ModPackManager;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Automatise l'étape "détection" du cycle de publication (voir docs/github-mod-format.md) :
 * relance périodiquement GitHubIntegration.importer().refreshAll() (le même appel que
 * /mod github refresh tapé à la main), et pour chaque mod dont une nouvelle version vient
 * d'être enregistrée dans le ModRegistry, lance automatiquement ModPackManager.prepareMod(modId)
 * — téléchargement + vérification SHA-256 + ajout à NEXT, exactement ce que ferait un
 * administrateur avec /modpack prepare <modId>.
 *
 * Ce que ce scheduler NE fait JAMAIS : préparer un mod qui n'est pas déjà ACCEPTED (refreshAll()
 * ne considère que les mods ACCEPTED), ni appeler /modpack apply, ni toucher CURRENT de quelque
 * façon que ce soit. La promotion NEXT -> CURRENT reste une décision manuelle distincte, pour ne
 * jamais transformer une publication GitHub en déploiement immédiat sans contrôle humain.
 */
public final class GitHubRefreshScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String LOG_PREFIX = "[CraftLabCore][GitHubRefresh] ";

    private static ScheduledExecutorService executor;

    private GitHubRefreshScheduler() {
    }

    public static synchronized void start(MinecraftServer server) {
        stop(); // évite les doublons si start() est appelé deux fois

        long intervalMinutes = GitHubRefreshConfig.getIntervalMinutes();
        if (intervalMinutes <= 0) {
            LOGGER.info(LOG_PREFIX + "Désactivé (github_refresh_interval_minutes <= 0). "
                + "/mod github refresh reste disponible manuellement.");
            return;
        }

        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "CraftLabCore-GitHubRefreshScheduler");
            thread.setDaemon(true);
            return thread;
        };

        executor = Executors.newSingleThreadScheduledExecutor(threadFactory);
        executor.scheduleAtFixedRate(
            () -> runOnce(server),
            intervalMinutes, intervalMinutes, TimeUnit.MINUTES
        );
        LOGGER.info(LOG_PREFIX + "Démarré (vérification toutes les " + intervalMinutes + " minute(s)).");
    }

    public static synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private static void runOnce(MinecraftServer server) {
        GitHubIntegration.importer().refreshAll().whenComplete((entries, throwable) ->
            server.execute(() -> {
                if (throwable != null) {
                    LOGGER.warn(LOG_PREFIX + "Échec de la vérification automatique : " + throwable.getMessage());
                    return;
                }
                logSummary(entries);
                prepareUpdated(entries);
            })
        );
    }

    private static void logSummary(List<GitHubModImporter.RefreshEntry> entries) {
        for (var entry : entries) {
            if (!entry.result().isSuccess()) {
                LOGGER.warn(LOG_PREFIX + entry.modId() + " : " + entry.result().getMessage());
            }
        }
    }

    /** Prépare séquentiellement (jamais en parallèle, pour ne jamais faire deux écritures NEXT concurrentes). */
    private static void prepareUpdated(List<GitHubModImporter.RefreshEntry> entries) {
        List<String> updated = GitHubModImporter.updatedModIds(entries);
        if (updated.isEmpty()) {
            return;
        }

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String modId : updated) {
            chain = chain.thenCompose(ignored -> ModPackManager.get().prepareMod(modId).thenAccept(result -> {
                LOGGER.info(LOG_PREFIX + "Nouvelle version détectée pour '" + modId + "', préparation NEXT : " + result
                    + " (CURRENT non modifié - /modpack diff puis /modpack apply restent manuels).");
            }));
        }
    }
}
