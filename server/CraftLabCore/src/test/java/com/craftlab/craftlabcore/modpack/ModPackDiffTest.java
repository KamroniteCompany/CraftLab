package com.craftlab.craftlabcore.modpack;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModPackDiff.compute() est la logique qui décide, entre CURRENT et NEXT, quels mods sont
 * ajoutés/mis à jour/retirés/inchangés — le cœur de ce que /modpack apply déploie réellement.
 * Méthode pure (aucune dépendance FMLPaths/ModRegistry), testable sans environnement Forge/FML.
 */
class ModPackDiffTest {

    private static ModPackEntry entry(String modId, String version) {
        return new ModPackEntry(modId, modId, version, "GITHUB", "v" + version, 1L,
            modId + "-" + version + ".jar", "sha-" + version, 100L, ModPackEntryStatus.READY);
    }

    private static ModPack packOf(ModPackEntry... entries) {
        ModPack pack = new ModPack("1.21.1", "52.1.0");
        for (ModPackEntry e : entries) {
            pack.upsert(e);
        }
        return pack;
    }

    @Test
    void identicalPacks_produceAnEmptyDiff() {
        ModPack current = packOf(entry("craftlabcore", "1.0.1"), entry("blankmod", "1.0.0"));
        ModPack next = packOf(entry("craftlabcore", "1.0.1"), entry("blankmod", "1.0.0"));

        ModPackDiff diff = ModPackDiff.compute(current, next);

        assertTrue(diff.isEmpty(), "aucun changement ne doit être détecté entre deux ModPack identiques");
        assertEquals(2, diff.getUnchanged().size());
    }

    @Test
    void newModInNext_isReportedAsAdded() {
        ModPack current = packOf(entry("craftlabcore", "1.0.1"));
        ModPack next = packOf(entry("craftlabcore", "1.0.1"), entry("blankmod", "1.0.0"));

        ModPackDiff diff = ModPackDiff.compute(current, next);

        assertEquals(List.of("blankmod"), diff.getAdded().stream().map(ModPackEntry::getModId).toList());
        assertTrue(diff.getUpdated().isEmpty());
        assertTrue(diff.getRemoved().isEmpty());
    }

    @Test
    void versionChange_isReportedAsUpdated() {
        ModPack current = packOf(entry("blankmod", "1.0.0"));
        ModPack next = packOf(entry("blankmod", "1.1.0"));

        ModPackDiff diff = ModPackDiff.compute(current, next);

        assertEquals(1, diff.getUpdated().size());
        ModPackDiff.UpdatedEntry updated = diff.getUpdated().get(0);
        assertEquals("1.0.0", updated.from().getVersion());
        assertEquals("1.1.0", updated.to().getVersion());
        assertTrue(diff.getAdded().isEmpty());
        assertTrue(diff.getRemoved().isEmpty());
    }

    @Test
    void modAbsentFromNext_isReportedAsRemoved() {
        ModPack current = packOf(entry("craftlabcore", "1.0.1"), entry("blankmod", "1.0.0"));
        ModPack next = packOf(entry("craftlabcore", "1.0.1"));

        ModPackDiff diff = ModPackDiff.compute(current, next);

        assertEquals(List.of("blankmod"), diff.getRemoved().stream().map(ModPackEntry::getModId).toList());
        assertTrue(diff.getAdded().isEmpty());
        assertTrue(diff.getUpdated().isEmpty());
    }

    @Test
    void nullCurrent_treatsEverythingInNextAsAdded() {
        ModPack next = packOf(entry("craftlabcore", "1.0.1"));

        ModPackDiff diff = ModPackDiff.compute(null, next);

        assertEquals(1, diff.getAdded().size());
        assertTrue(diff.getRemoved().isEmpty());
    }

    @Test
    void mixedBatch_classifiesEachModIndependently() {
        ModPack current = packOf(entry("craftlabcore", "1.0.1"), entry("blankmod", "1.0.0"), entry("oldmod", "1.0.0"));
        ModPack next = packOf(entry("craftlabcore", "1.0.1"), entry("blankmod", "1.1.0"), entry("newmod", "1.0.0"));

        ModPackDiff diff = ModPackDiff.compute(current, next);

        assertEquals(List.of("newmod"), diff.getAdded().stream().map(ModPackEntry::getModId).toList());
        assertEquals(List.of("oldmod"), diff.getRemoved().stream().map(ModPackEntry::getModId).toList());
        assertEquals(1, diff.getUpdated().size());
        assertEquals("blankmod", diff.getUpdated().get(0).to().getModId());
        assertEquals(List.of("craftlabcore"), diff.getUnchanged().stream().map(ModPackEntry::getModId).toList());
        assertTrue(!diff.isEmpty());
    }
}
