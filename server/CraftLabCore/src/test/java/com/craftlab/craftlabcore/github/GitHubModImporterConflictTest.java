package com.craftlab.craftlabcore.github;

import com.craftlab.craftlabcore.mod.ModDefinition;
import com.craftlab.craftlabcore.mod.ModSource;
import com.craftlab.craftlabcore.mod.ModStatus;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Couvre les 4 cas du correctif du bug réel : craftlabcore existait déjà dans le ModRegistry
 * (bootstrap, sans source GitHub) mais /mod github import le traitait comme un conflit d'ID
 * au lieu d'autoriser le rattachement. isConflict() est une méthode pure (aucune dépendance à
 * ModRegistry/FMLPaths), donc testable sans environnement Forge/FML.
 */
class GitHubModImporterConflictTest {

    private final GitHubModImporter importer = new GitHubModImporter(null);

    private static final GitHubRepository CRAFTLAB_REPO =
        new GitHubRepository("KamroniteCompany", "CraftLab", "https://github.com/KamroniteCompany/CraftLab",
            null, null, 0, null);

    @Test
    void nonExistentMod_isNotAConflict() {
        assertFalse(importer.isConflict(Optional.empty(), CRAFTLAB_REPO),
            "cas 1 : un mod inexistant doit permettre une création normale");
    }

    @Test
    void existingModWithoutGitHubSource_isNotAConflict() {
        ModDefinition craftlabcore = new ModDefinition("craftlabcore", "CraftLabCore", "OpenSourceDev",
            "1.0.0", "Mod principal de CraftLab.", ModStatus.ACCEPTED);
        // Cas exact du bug : bootstrap de ModRegistry, jamais lié à GitHub.
        assertFalse(craftlabcore.getSource() != null, "précondition : pas encore de source");

        assertFalse(importer.isConflict(Optional.of(craftlabcore), CRAFTLAB_REPO),
            "cas 2 : un mod existant sans source GitHub doit pouvoir être rattaché");
    }

    @Test
    void existingModWithSameRepository_isNotAConflict() {
        ModDefinition craftlabcore = new ModDefinition("craftlabcore", "CraftLabCore", "OpenSourceDev",
            "1.0.0", "Mod principal de CraftLab.", ModStatus.ACCEPTED);
        craftlabcore.setSource(new ModSource(ModSource.TYPE_GITHUB, "KamroniteCompany", "CraftLab",
            "https://github.com/KamroniteCompany/CraftLab"));

        assertFalse(importer.isConflict(Optional.of(craftlabcore), CRAFTLAB_REPO),
            "cas 3 : un mod déjà lié au même repository doit pouvoir être mis à jour");
    }

    @Test
    void existingModWithDifferentRepository_isAConflict() {
        ModDefinition otherMod = new ModDefinition("craftlabcore", "AutreMod", "QuelquUnDautre",
            "1.0.0", "Un autre mod utilisant le même ID.", ModStatus.ACCEPTED);
        otherMod.setSource(new ModSource(ModSource.TYPE_GITHUB, "AutreProprietaire", "AutreRepo",
            "https://github.com/AutreProprietaire/AutreRepo"));

        assertTrue(importer.isConflict(Optional.of(otherMod), CRAFTLAB_REPO),
            "cas 4 : un mod déjà lié à un AUTRE repository doit rester un conflit refusé");
    }
}
