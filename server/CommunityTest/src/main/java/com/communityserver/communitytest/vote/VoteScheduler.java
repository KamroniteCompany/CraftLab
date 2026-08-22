package com.communityserver.communitytest.vote;

import com.communityserver.communitytest.proposal.ProposalManager;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Vérifie chaque seconde si une ou plusieurs propositions doivent expirer, indépendamment
 * de tout évènement Forge de tick (dont l'API interne a changé récemment et n'est pas stable
 * d'une version de build à l'autre). Le vrai travail est repoussé sur le thread principal
 * du serveur via MinecraftServer#execute pour rester thread-safe.
 */
public final class VoteScheduler {

    private static ScheduledExecutorService executor;

    private VoteScheduler() {
    }

    public static synchronized void start(MinecraftServer server) {
        stop(); // évite les doublons si start() est appelé deux fois

        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "CommunityTest-VoteScheduler");
            thread.setDaemon(true);
            return thread;
        };

        executor = Executors.newSingleThreadScheduledExecutor(threadFactory);
        executor.scheduleAtFixedRate(
            () -> server.execute(() -> ProposalManager.get().tickAll(server)),
            1, 1, TimeUnit.SECONDS
        );
    }

    public static synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
