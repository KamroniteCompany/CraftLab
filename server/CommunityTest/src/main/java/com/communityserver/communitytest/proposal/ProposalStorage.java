package com.communityserver.communitytest.proposal;

import com.communityserver.communitytest.vote.VoteChoice;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persiste, par mod, sa proposition la plus récente dans
 * config/communitytest/proposals/&lt;modId&gt;.json.
 */
public class ProposalStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FOLDER_NAME = "communitytest";
    private static final String SUBFOLDER_NAME = "proposals";
    private static final String EXTENSION = ".json";

    private final Path folderPath;

    public ProposalStorage() {
        this.folderPath = FMLPaths.CONFIGDIR.get().resolve(FOLDER_NAME).resolve(SUBFOLDER_NAME);
    }

    private Path fileFor(String modId) {
        return folderPath.resolve(modId + EXTENSION);
    }

    public ModProposal load(String modId) {
        Path filePath = fileFor(modId);
        if (!Files.exists(filePath)) {
            return null;
        }

        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) {
                return null;
            }

            ModProposal proposal = new ModProposal();
            proposal.setProposalId(getString(root, "proposalId", modId + "-1"));
            proposal.setModId(getString(root, "modId", modId));
            proposal.setStatus(ProposalStatus.valueOf(getString(root, "status", "TESTING")));
            proposal.setStartTime(root.has("startTime") ? root.get("startTime").getAsLong() : 0L);
            proposal.setEndTime(root.has("endTime") ? root.get("endTime").getAsLong() : 0L);

            if (root.has("votes") && root.get("votes").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("votes").entrySet()) {
                    try {
                        UUID uuid = UUID.fromString(entry.getKey());
                        VoteChoice choice = VoteChoice.valueOf(entry.getValue().getAsString());
                        proposal.getVotes().put(uuid, choice);
                    } catch (IllegalArgumentException ignored) {
                        // Entrée corrompue (UUID ou valeur invalide) : ignorée.
                    }
                }
            }

            return proposal;
        } catch (IOException | JsonParseException | IllegalArgumentException e) {
            return null;
        }
    }

    public void save(ModProposal proposal) {
        if (proposal == null) {
            return;
        }

        Path filePath = fileFor(proposal.getModId());
        try {
            Files.createDirectories(filePath.getParent());
        } catch (IOException e) {
            return;
        }

        JsonObject root = new JsonObject();
        root.addProperty("proposalId", proposal.getProposalId());
        root.addProperty("modId", proposal.getModId());
        root.addProperty("status", proposal.getStatus().name());
        root.addProperty("startTime", proposal.getStartTime());
        root.addProperty("endTime", proposal.getEndTime());

        JsonObject votesObj = new JsonObject();
        for (Map.Entry<UUID, VoteChoice> entry : proposal.getVotes().entrySet()) {
            votesObj.addProperty(entry.getKey().toString(), entry.getValue().name());
        }
        root.add("votes", votesObj);

        try (BufferedWriter writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException ignored) {
            // Pas bloquant ; la prochaine sauvegarde réessaiera.
        }
    }

    /** Liste les modId ayant un fichier de proposition, pour recharger ProposalManager au démarrage. */
    public List<String> listModIdsWithProposal() {
        List<String> ids = new ArrayList<>();
        if (!Files.isDirectory(folderPath)) {
            return ids;
        }

        try (var stream = Files.list(folderPath)) {
            stream.filter(p -> p.getFileName().toString().endsWith(EXTENSION))
                .forEach(p -> {
                    String fileName = p.getFileName().toString();
                    ids.add(fileName.substring(0, fileName.length() - EXTENSION.length()));
                });
        } catch (IOException ignored) {
            // Dossier illisible : on repart d'une liste vide.
        }

        return ids;
    }

    private static String getString(JsonObject obj, String key, String defaultValue) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : defaultValue;
    }
}
