package com.craftlab.craftlabcore.github;

import com.craftlab.craftlabcore.mod.ModDefinition;
import com.craftlab.craftlabcore.mod.ModStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Couvre updatedModIds(), le filtre utilisé par GitHubRefreshScheduler pour décider quels mods
 * lancer en /modpack prepare automatique après un refreshAll() : seule une version réellement
 * différente d'un import réussi doit déclencher une préparation. Méthode pure (aucune dépendance
 * à ModRegistry/FMLPaths), testable sans environnement Forge/FML.
 */
class GitHubModImporterUpdatedModIdsTest {

    private static ModDefinition modAt(String id, String version) {
        return new ModDefinition(id, id, "Auteur", version, "Description", ModStatus.ACCEPTED);
    }

    @Test
    void versionChanged_isReportedAsUpdated() {
        var entry = new GitHubModImporter.RefreshEntry("blankmod",
            ImportResult.success(modAt("blankmod", "1.1.0"), true), "1.0.0");

        assertEquals(List.of("blankmod"), GitHubModImporter.updatedModIds(List.of(entry)));
    }

    @Test
    void versionUnchanged_isNotReportedAsUpdated() {
        var entry = new GitHubModImporter.RefreshEntry("craftlabcore",
            ImportResult.success(modAt("craftlabcore", "1.0.1"), false), "1.0.1");

        assertTrue(GitHubModImporter.updatedModIds(List.of(entry)).isEmpty());
    }

    @Test
    void failedRefresh_isNeverReportedAsUpdated() {
        // Même si la version "attendue" est différente de la dernière connue, un échec (GitHub
        // inaccessible, etc.) n'a pas mis à jour le ModRegistry : rien à préparer.
        var entry = new GitHubModImporter.RefreshEntry("craftlabcore",
            ImportResult.failure(ImportResult.Status.GITHUB_UNAVAILABLE, "GitHub inaccessible"), "1.0.1");

        assertTrue(GitHubModImporter.updatedModIds(List.of(entry)).isEmpty());
    }

    @Test
    void mixedBatch_onlyReturnsTheUpdatedOnes() {
        var updated = new GitHubModImporter.RefreshEntry("blankmod",
            ImportResult.success(modAt("blankmod", "1.1.0"), true), "1.0.0");
        var upToDate = new GitHubModImporter.RefreshEntry("craftlabcore",
            ImportResult.success(modAt("craftlabcore", "1.0.1"), false), "1.0.1");
        var failed = new GitHubModImporter.RefreshEntry("other",
            ImportResult.failure(ImportResult.Status.RATE_LIMITED, "Rate limited"), "1.0.0");

        assertEquals(List.of("blankmod"), GitHubModImporter.updatedModIds(List.of(updated, upToDate, failed)));
    }
}
