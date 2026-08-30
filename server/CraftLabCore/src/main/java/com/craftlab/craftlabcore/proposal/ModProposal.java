package com.craftlab.craftlabcore.proposal;

import com.craftlab.craftlabcore.vote.VoteChoice;
import com.craftlab.craftlabcore.vote.VoteManager;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Représente le cycle de test/vote d'un mod donné. Pour cette version, un seul ModProposal
 * est conservé par modId (le plus récent) — mais proposalId ("modId-N") numérote déjà les
 * propositions successives, donc rien n'empêche plus tard de garder aussi les anciennes.
 */
public class ModProposal {

    private String proposalId;
    private String modId;
    private ProposalStatus status;
    private long startTime; // epoch seconds
    private long endTime;   // epoch seconds
    private final Map<UUID, VoteChoice> votes = new HashMap<>();

    /** Constructeur requis pour la désérialisation depuis ProposalStorage. */
    public ModProposal() {
    }

    public ModProposal(String proposalId, String modId, long startTime, long endTime) {
        this.proposalId = proposalId;
        this.modId = modId;
        this.status = ProposalStatus.TESTING;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public String getProposalId() {
        return proposalId;
    }

    public void setProposalId(String proposalId) {
        this.proposalId = proposalId;
    }

    public String getModId() {
        return modId;
    }

    public void setModId(String modId) {
        this.modId = modId;
    }

    public ProposalStatus getStatus() {
        return status;
    }

    public void setStatus(ProposalStatus status) {
        this.status = status;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public Map<UUID, VoteChoice> getVotes() {
        return votes;
    }

    public int countYes() {
        return VoteManager.countYes(votes);
    }

    public int countNo() {
        return VoteManager.countNo(votes);
    }

    public long secondsRemaining() {
        return Math.max(0, endTime - Instant.now().getEpochSecond());
    }
}
