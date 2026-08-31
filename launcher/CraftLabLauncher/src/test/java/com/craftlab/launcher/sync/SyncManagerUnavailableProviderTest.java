package com.craftlab.launcher.sync;

import com.craftlab.launcher.instance.InstancePaths;
import com.craftlab.launcher.modpack.ModPackFetchException;
import com.craftlab.launcher.modpack.ModPackProvider;
import com.craftlab.launcher.state.LauncherState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quand le serveur CraftLab (ou GitHub/l'hébergeur du ModPack) est indisponible,
 * SyncManager.sync() doit échouer proprement et rapidement : jamais rester bloqué, toujours
 * notifier ERROR au listener, jamais laisser une exception s'échapper autrement que via le
 * CompletableFuture retourné (voir le commentaire de sync() sur ce point précis).
 */
class SyncManagerUnavailableProviderTest {

    @Test
    void providerFailure_notifiesErrorState_andCompletesExceptionally_withoutHanging(@TempDir Path tempDir) throws Exception {
        InstancePaths paths = InstancePaths.at(tempDir);
        ModPackProvider failingProvider = () ->
            CompletableFuture.failedFuture(new ModPackFetchException("Le serveur CraftLab est injoignable."));

        List<LauncherState> statesSeen = new ArrayList<>();
        SyncManager.Listener listener = new SyncManager.Listener() {
            @Override
            public void onStateChanged(LauncherState state) {
                statesSeen.add(state);
            }

            @Override
            public void onModProgress(String modId, long bytesWritten, long totalBytesHint) {
            }

            @Override
            public void onLog(String message) {
            }
        };

        SyncManager syncManager = new SyncManager(failingProvider, paths);
        CompletableFuture<SyncPlan> result = syncManager.sync(listener);

        ExecutionException thrown = assertThrows(ExecutionException.class,
            () -> result.get(10, TimeUnit.SECONDS), "un serveur indisponible ne doit jamais faire attendre indéfiniment");

        assertTrue(thrown.getCause() instanceof CompletionException || thrown.getCause() instanceof ModPackFetchException);
        assertEquals(List.of(LauncherState.CHECKING, LauncherState.ERROR), statesSeen,
            "le listener doit être notifié CHECKING puis ERROR, jamais laissé sans nouvelle");
    }

    @Test
    void synchronousProviderException_isAlsoHandledGracefully(@TempDir Path tempDir) throws Exception {
        // Un ModPackProvider "mal écrit" qui lève directement au lieu de renvoyer un future en
        // échec (voir le commentaire de sync() sur ce point précis) ne doit pas non plus faire
        // planter le launcher hors du CompletableFuture.
        InstancePaths paths = InstancePaths.at(tempDir);
        ModPackProvider throwingProvider = () -> {
            throw new RuntimeException("Erreur inattendue avant même de commencer la requête.");
        };

        SyncManager syncManager = new SyncManager(throwingProvider, paths);
        CompletableFuture<SyncPlan> result = syncManager.sync(com.craftlab.launcher.sync.SyncTestSupport.noopListener());

        assertThrows(ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS));
    }
}
