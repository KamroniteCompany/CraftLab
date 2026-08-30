package com.craftlab.craftlabcore.proposal;

import com.craftlab.craftlabcore.mod.ModDefinition;
import com.craftlab.craftlabcore.mod.ModRegistry;
import com.craftlab.craftlabcore.mod.ModStatus;
import com.craftlab.craftlabcore.vote.VoteChoice;
import com.craftlab.craftlabcore.vote.VoteConfig;
import com.craftlab.craftlabcore.vote.VoteManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gère le cycle de vie des propositions, une par mod pour cette version.
 * N'accorde AUCUN traitement spécial à "craftlabcore" (le mod cœur lui-même) : tout modId
 * est traité de façon strictement identique ici.
 */
public final class ProposalManager {

    private static final ProposalManager INSTANCE = new ProposalManager();

    public static ProposalManager get() {
        return INSTANCE;
    }

    public enum StartResult { STARTED, ALREADY_ACTIVE, UNKNOWN_MOD }

    public enum EndResult { ENDED, NO_ACTIVE_PROPOSAL }

    public enum CastResult { UNKNOWN_MOD, NO_ACTIVE_PROPOSAL, ALREADY_VOTED_SAME, REGISTERED, CHANGED }

    private static final Pattern TRAILING_NUMBER = Pattern.compile("-(\\d+)$");

    private final ProposalStorage storage = new ProposalStorage();
    private final Map<String, ModProposal> proposals = new HashMap<>();

    private ProposalManager() {
    }

    /** Recharge toutes les propositions persistées, une par fichier trouvé dans proposals/. */
    public synchronized void load() {
        proposals.clear();
        for (String modId : storage.listModIdsWithProposal()) {
            ModProposal proposal = storage.load(modId);
            if (proposal != null) {
                proposals.put(modId, proposal);
            }
        }
    }

    public synchronized Optional<ModProposal> get(String modId) {
        return Optional.ofNullable(proposals.get(modId));
    }

    public synchronized StartResult startProposal(String modId, MinecraftServer server) {
        if (!ModRegistry.get().exists(modId)) {
            return StartResult.UNKNOWN_MOD;
        }

        ModProposal existing = proposals.get(modId);
        if (existing != null && existing.getStatus() == ProposalStatus.TESTING) {
            return StartResult.ALREADY_ACTIVE;
        }

        // Relit la config à chaque démarrage : pratique pour tester différentes durées
        // sans redémarrer le serveur.
        VoteConfig.load();
        long durationSeconds = VoteConfig.getVoteDurationSeconds();

        long now = Instant.now().getEpochSecond();
        String proposalId = modId + "-" + nextProposalNumber(existing);

        ModProposal proposal = new ModProposal(proposalId, modId, now, now + durationSeconds);
        proposals.put(modId, proposal);
        ModRegistry.get().updateStatus(modId, ModStatus.TESTING);
        persist(proposal);
        announceStart(server, modId, durationSeconds);
        return StartResult.STARTED;
    }

    public synchronized EndResult endProposal(String modId, MinecraftServer server) {
        ModProposal proposal = proposals.get(modId);
        if (proposal == null || proposal.getStatus() != ProposalStatus.TESTING) {
            return EndResult.NO_ACTIVE_PROPOSAL;
        }
        resolve(proposal, server);
        return EndResult.ENDED;
    }

    public synchronized CastResult castVote(String modId, UUID playerUuid, VoteChoice choice) {
        if (!ModRegistry.get().exists(modId)) {
            return CastResult.UNKNOWN_MOD;
        }

        ModProposal proposal = proposals.get(modId);
        if (proposal == null || proposal.getStatus() != ProposalStatus.TESTING) {
            return CastResult.NO_ACTIVE_PROPOSAL;
        }

        VoteManager.CastResult result = VoteManager.castVote(proposal.getVotes(), playerUuid, choice);
        if (result != VoteManager.CastResult.ALREADY_VOTED_SAME) {
            persist(proposal);
        }

        return switch (result) {
            case ALREADY_VOTED_SAME -> CastResult.ALREADY_VOTED_SAME;
            case REGISTERED -> CastResult.REGISTERED;
            case CHANGED -> CastResult.CHANGED;
        };
    }

    /**
     * Appelé chaque seconde par VoteScheduler, déjà sur le thread principal du serveur.
     * Parcourt TOUTES les propositions actives : chaque mod a son propre endTime, donc
     * plusieurs votes peuvent se terminer à des moments complètement différents.
     */
    public synchronized void tickAll(MinecraftServer server) {
        long now = Instant.now().getEpochSecond();
        for (ModProposal proposal : proposals.values()) {
            if (proposal.getStatus() == ProposalStatus.TESTING && now >= proposal.getEndTime()) {
                resolve(proposal, server);
            }
        }
    }

    private void resolve(ModProposal proposal, MinecraftServer server) {
        int yes = proposal.countYes();
        int no = proposal.countNo();
        ProposalStatus result = yes > no ? ProposalStatus.ACCEPTED : ProposalStatus.REJECTED;
        proposal.setStatus(result);
        persist(proposal);

        ModStatus modStatus = result == ProposalStatus.ACCEPTED ? ModStatus.ACCEPTED : ModStatus.REJECTED;
        ModRegistry.get().updateStatus(proposal.getModId(), modStatus);

        announceEnd(server, proposal.getModId(), yes, no, result);
    }

    private void persist(ModProposal proposal) {
        storage.save(proposal);
    }

    private int nextProposalNumber(ModProposal existing) {
        if (existing == null || existing.getProposalId() == null) {
            return 1;
        }
        Matcher matcher = TRAILING_NUMBER.matcher(existing.getProposalId());
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1)) + 1;
            } catch (NumberFormatException ignored) {
                // repli sur 1 ci-dessous
            }
        }
        return 1;
    }

    private void announceStart(MinecraftServer server, String modId, long durationSeconds) {
        String name = ModRegistry.get().get(modId).map(ModDefinition::getName).orElse(modId);
        String message = "\n§b🧪 NOUVEAU VOTE§r\n\n"
            + "Le mod " + name + " est actuellement en période de test.\n\n"
            + "Votez avec :\n"
            + "/mod vote " + modId + " yes\n"
            + "/mod vote " + modId + " no\n\n"
            + "Durée : " + VoteManager.formatDuration(durationSeconds) + "\n";
        server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
    }

    private void announceEnd(MinecraftServer server, String modId, int yes, int no, ProposalStatus result) {
        String name = ModRegistry.get().get(modId).map(ModDefinition::getName).orElse(modId);
        String message = "\n§6🏁 VOTE TERMINÉ§r\n\n"
            + "Mod : " + name + "\n\n"
            + "👍 Pour : " + yes + "\n"
            + "👎 Contre : " + no + "\n\n"
            + "Résultat : " + (result == ProposalStatus.ACCEPTED ? "ACCEPTÉ" : "REJETÉ") + "\n";
        server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
    }
}
