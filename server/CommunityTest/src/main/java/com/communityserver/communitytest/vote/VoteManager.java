package com.communityserver.communitytest.vote;

import java.util.Map;
import java.util.UUID;

/**
 * Logique PURE de dépouillement : ne connaît ni mod, ni proposition, ni persistance,
 * ni annonce. Opère uniquement sur la carte des votes d'une proposition donnée, passée
 * en paramètre. Toute l'orchestration (démarrage, fin, annonces, sauvegarde) vit dans
 * proposal.ProposalManager, qui délègue ici le dépouillement lui-même.
 */
public final class VoteManager {

    public enum CastResult { ALREADY_VOTED_SAME, REGISTERED, CHANGED }

    private VoteManager() {
    }

    /**
     * Enregistre ou modifie le vote d'un joueur. Revoter exactement le même choix est
     * un no-op signalé par ALREADY_VOTED_SAME ; voter un choix différent modifie le vote.
     */
    public static CastResult castVote(Map<UUID, VoteChoice> votes, UUID playerUuid, VoteChoice choice) {
        VoteChoice previous = votes.get(playerUuid);
        if (previous == choice) {
            return CastResult.ALREADY_VOTED_SAME;
        }
        votes.put(playerUuid, choice);
        return previous == null ? CastResult.REGISTERED : CastResult.CHANGED;
    }

    public static int countYes(Map<UUID, VoteChoice> votes) {
        return (int) votes.values().stream().filter(choice -> choice == VoteChoice.YES).count();
    }

    public static int countNo(Map<UUID, VoteChoice> votes) {
        return (int) votes.values().stream().filter(choice -> choice == VoteChoice.NO).count();
    }

    public static String formatDuration(long totalSeconds) {
        if (totalSeconds <= 0) {
            return "0 seconde";
        }

        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append(days > 1 ? " jours " : " jour ");
        }
        if (hours > 0) {
            sb.append(hours).append(hours > 1 ? " heures " : " heure ");
        }
        if (days == 0 && hours == 0 && minutes > 0) {
            sb.append(minutes).append(minutes > 1 ? " minutes " : " minute ");
        }
        if (days == 0 && hours == 0 && minutes == 0) {
            sb.append(seconds).append(seconds > 1 ? " secondes" : " seconde");
        }
        return sb.toString().trim();
    }
}
