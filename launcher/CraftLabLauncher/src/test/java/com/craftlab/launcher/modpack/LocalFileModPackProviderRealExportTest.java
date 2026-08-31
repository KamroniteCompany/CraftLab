package com.craftlab.launcher.modpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Utilise le contenu RÉEL de current-modpack-launcher.json produit par
 * LauncherModPackExporter côté serveur (relevé sur le serveur réel le 2026-08-31, CURRENT
 * generation 3 : blankmod 1.0.0 + craftlabcore 1.0.1) pour vérifier que le launcher parse
 * effectivement les 7 champs requis (modId, name, version, assetName, sha256, size,
 * downloadUrl) pour chaque mod, sans dépendre d'un JSON de test artificiel qui pourrait diverger
 * du vrai format produit par le serveur.
 */
class LocalFileModPackProviderRealExportTest {

    private static final String REAL_EXPORT = """
        {
          "minecraftVersion": "1.21.1",
          "forgeVersion": "52.1.0",
          "generation": 3,
          "mods": [
            {
              "modId": "blankmod",
              "name": "BlankMod",
              "version": "1.0.0",
              "assetName": "blankmod-1.0.0.jar",
              "sha256": "c131e1e417f08b7b246fb94a980d97190ef55fa3db21b9459f093f9c2a059e3b",
              "size": 2029,
              "downloadUrl": "https://github.com/KamroniteCompany/CraftLabTest/releases/download/v1.0.0/blankmod-1.0.0.jar"
            },
            {
              "modId": "craftlabcore",
              "name": "CraftLabCore",
              "version": "1.0.1",
              "assetName": "craftlabcore-1.0.1.jar",
              "sha256": "cefb52212347367afc8efd7fcd823f836ce79a3200dc3fbbff5648ae3b67eda1",
              "size": 144801,
              "downloadUrl": "https://github.com/KamroniteCompany/CraftLab/releases/download/v1.0.1/craftlabcore-1.0.1.jar"
            }
          ]
        }""";

    @Test
    void parsesEveryRequiredFieldForEachMod(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("current-modpack-launcher.json");
        Files.writeString(file, REAL_EXPORT, StandardCharsets.UTF_8);

        RemoteModPack remote = new LocalFileModPackProvider(file).getCurrentModPack().get(5, TimeUnit.SECONDS);

        assertEquals("1.21.1", remote.minecraftVersion());
        assertEquals("52.1.0", remote.forgeVersion());
        assertEquals(3, remote.generation());
        assertEquals(2, remote.mods().size());

        RemoteModEntry blankmod = remote.mods().get(0);
        assertEquals("blankmod", blankmod.modId());
        assertEquals("BlankMod", blankmod.name());
        assertEquals("1.0.0", blankmod.version());
        assertEquals("blankmod-1.0.0.jar", blankmod.assetName());
        assertEquals("c131e1e417f08b7b246fb94a980d97190ef55fa3db21b9459f093f9c2a059e3b", blankmod.sha256());
        assertEquals(2029L, blankmod.size());
        assertEquals("https://github.com/KamroniteCompany/CraftLabTest/releases/download/v1.0.0/blankmod-1.0.0.jar",
            blankmod.downloadUrl());

        RemoteModEntry craftlabcore = remote.mods().get(1);
        assertEquals("craftlabcore", craftlabcore.modId());
        assertEquals("1.0.1", craftlabcore.version());
        assertEquals("cefb52212347367afc8efd7fcd823f836ce79a3200dc3fbbff5648ae3b67eda1", craftlabcore.sha256());
        assertEquals(144801L, craftlabcore.size());
    }
}
