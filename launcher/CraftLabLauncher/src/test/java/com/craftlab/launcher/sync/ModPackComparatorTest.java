package com.craftlab.launcher.sync;

import com.craftlab.launcher.instance.InstalledModEntry;
import com.craftlab.launcher.instance.InstalledModPack;
import com.craftlab.launcher.modpack.RemoteModEntry;
import com.craftlab.launcher.modpack.RemoteModPack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModPackComparator.compare() décide, pour chaque mod du ModPack distant, s'il faut le
 * télécharger ou non — la décision centrale de toute synchronisation. Méthode pure (aucun I/O,
 * aucune dépendance), donc couverte ici indépendamment de SyncManager (déjà couvert par
 * SyncManagerActiveFileCleanupTest / SyncManagerCorruptedActiveFileTest pour les scénarios qui
 * impliquent le disque réel).
 */
class ModPackComparatorTest {

    private static RemoteModEntry remote(String modId, String version, String sha) {
        return new RemoteModEntry(modId, modId, version, modId + "-" + version + ".jar", sha, 100L,
            "https://example.invalid/" + modId + "-" + version + ".jar");
    }

    private static InstalledModEntry installed(String modId, String version, String sha) {
        return new InstalledModEntry(modId, version, modId + "-" + version + ".jar", sha, 100L);
    }

    @Test
    void identicalModPack_downloadsNothing() {
        RemoteModPack remotePack = new RemoteModPack("1.21.1", "52.1.0", 1, List.of(
            remote("craftlabcore", "1.0.1", "sha-core"), remote("blankmod", "1.0.0", "sha-blank")));
        InstalledModPack local = new InstalledModPack("1.21.1", "52.1.0", 1, List.of(
            installed("craftlabcore", "1.0.1", "sha-core"), installed("blankmod", "1.0.0", "sha-blank")));

        SyncPlan plan = ModPackComparator.compare(remotePack, local);

        assertTrue(plan.isEmpty(), "un ModPack strictement identique ne doit déclencher aucun téléchargement");
        assertEquals(2, plan.upToDate().size());
    }

    @Test
    void newModInRemote_isPlannedForDownload() {
        RemoteModPack remotePack = new RemoteModPack("1.21.1", "52.1.0", 2, List.of(
            remote("craftlabcore", "1.0.1", "sha-core"), remote("blankmod", "1.0.0", "sha-blank")));
        InstalledModPack local = new InstalledModPack("1.21.1", "52.1.0", 1, List.of(
            installed("craftlabcore", "1.0.1", "sha-core")));

        SyncPlan plan = ModPackComparator.compare(remotePack, local);

        assertEquals(List.of("blankmod"), plan.toDownload().stream().map(RemoteModEntry::modId).toList());
        assertTrue(plan.toRemove().isEmpty());
    }

    @Test
    void versionBump_isPlannedForDownload_evenIfSha256Unknown() {
        RemoteModPack remotePack = new RemoteModPack("1.21.1", "52.1.0", 2, List.of(
            remote("blankmod", "1.1.0", "sha-new")));
        InstalledModPack local = new InstalledModPack("1.21.1", "52.1.0", 1, List.of(
            installed("blankmod", "1.0.0", "sha-old")));

        SyncPlan plan = ModPackComparator.compare(remotePack, local);

        assertEquals(1, plan.toDownload().size(), "1.0.0 -> 1.1.0 doit être détecté comme une mise à jour à appliquer");
        assertTrue(plan.upToDate().isEmpty());
    }

    @Test
    void modRemovedFromRemote_isPlannedForRemoval() {
        RemoteModPack remotePack = new RemoteModPack("1.21.1", "52.1.0", 2, List.of(
            remote("craftlabcore", "1.0.1", "sha-core")));
        InstalledModPack local = new InstalledModPack("1.21.1", "52.1.0", 1, List.of(
            installed("craftlabcore", "1.0.1", "sha-core"), installed("blankmod", "1.0.0", "sha-blank")));

        SyncPlan plan = ModPackComparator.compare(remotePack, local);

        assertEquals(List.of("blankmod"), plan.toRemove().stream().map(InstalledModEntry::modId).toList());
        assertTrue(plan.toDownload().isEmpty());
    }

    @Test
    void sameVersionButDifferentSha256_isStillPlannedForDownload() {
        // Le SHA-256, pas seulement le numéro de version, fait foi : un mod republié sous le même
        // numéro avec un contenu différent ne doit jamais être classé "à jour" par erreur.
        RemoteModPack remotePack = new RemoteModPack("1.21.1", "52.1.0", 2, List.of(
            remote("blankmod", "1.0.0", "sha-rebuilt")));
        InstalledModPack local = new InstalledModPack("1.21.1", "52.1.0", 1, List.of(
            installed("blankmod", "1.0.0", "sha-original")));

        SyncPlan plan = ModPackComparator.compare(remotePack, local);

        assertEquals(1, plan.toDownload().size());
    }

    @Test
    void noLocalInstallationYet_everythingIsPlannedForDownload() {
        RemoteModPack remotePack = new RemoteModPack("1.21.1", "52.1.0", 1, List.of(
            remote("craftlabcore", "1.0.1", "sha-core"), remote("blankmod", "1.0.0", "sha-blank")));

        SyncPlan plan = ModPackComparator.compare(remotePack, null);

        assertEquals(2, plan.toDownload().size());
        assertTrue(plan.toRemove().isEmpty());
    }
}
