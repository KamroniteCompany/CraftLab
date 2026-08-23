package com.communityserver.communitytest.modpack;

import java.util.ArrayList;
import java.util.List;

/** Comparaison pure (sans effet de bord) entre deux ModPack : ajouté / retiré / mis à jour / inchangé. */
public final class ModPackDiff {

    public record UpdatedEntry(ModPackEntry from, ModPackEntry to) {
    }

    private final List<ModPackEntry> added = new ArrayList<>();
    private final List<ModPackEntry> removed = new ArrayList<>();
    private final List<UpdatedEntry> updated = new ArrayList<>();
    private final List<ModPackEntry> unchanged = new ArrayList<>();

    private ModPackDiff() {
    }

    public static ModPackDiff compute(ModPack from, ModPack to) {
        ModPackDiff diff = new ModPackDiff();

        List<ModPackEntry> fromMods = from != null ? from.getMods() : List.of();
        List<ModPackEntry> toMods = to != null ? to.getMods() : List.of();

        for (ModPackEntry toEntry : toMods) {
            var fromEntry = fromMods.stream().filter(e -> e.getModId().equals(toEntry.getModId())).findFirst();
            if (fromEntry.isEmpty()) {
                diff.added.add(toEntry);
            } else if (!fromEntry.get().getVersion().equals(toEntry.getVersion())) {
                diff.updated.add(new UpdatedEntry(fromEntry.get(), toEntry));
            } else {
                diff.unchanged.add(toEntry);
            }
        }

        for (ModPackEntry fromEntry : fromMods) {
            boolean stillPresent = toMods.stream().anyMatch(e -> e.getModId().equals(fromEntry.getModId()));
            if (!stillPresent) {
                diff.removed.add(fromEntry);
            }
        }

        return diff;
    }

    public List<ModPackEntry> getAdded() {
        return added;
    }

    public List<ModPackEntry> getRemoved() {
        return removed;
    }

    public List<UpdatedEntry> getUpdated() {
        return updated;
    }

    public List<ModPackEntry> getUnchanged() {
        return unchanged;
    }

    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty() && updated.isEmpty();
    }
}
