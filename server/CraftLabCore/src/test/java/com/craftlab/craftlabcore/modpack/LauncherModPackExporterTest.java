package com.craftlab.craftlabcore.modpack;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LauncherModPackExporter.toJson() produit exactement current-modpack-launcher.json, le seul
 * fichier que lit le CraftLab Launcher (voir LocalFileModPackProviderRealExportTest côté
 * launcher, qui vérifie le format inverse). Vérifie ici les 7 champs requis, et surtout que
 * l'export ne reflète jamais que le ModPack qu'on lui passe explicitement (jamais un état global
 * NEXT/CURRENT caché) — l'appelant (export(ModPack current)) est ce qui garantit que c'est
 * toujours CURRENT, jamais NEXT.
 */
class LauncherModPackExporterTest {

    private static final Gson GSON = new Gson();

    @Test
    void includesAllSevenRequiredFieldsPerMod() {
        ModPack current = new ModPack("1.21.1", "52.1.0");
        current.setGeneration(3L);
        current.upsert(new ModPackEntry("craftlabcore", "CraftLabCore", "1.0.1", "GITHUB", "v1.0.1", 1L,
            "craftlabcore-1.0.1.jar", "sha-core", 144801L, ModPackEntryStatus.READY));

        String json = LauncherModPackExporter.toJson(current,
            modId -> "https://github.com/KamroniteCompany/CraftLab/releases/download/v1.0.1/craftlabcore-1.0.1.jar");

        JsonObject root = GSON.fromJson(json, JsonObject.class);
        assertEquals("1.21.1", root.get("minecraftVersion").getAsString());
        assertEquals("52.1.0", root.get("forgeVersion").getAsString());
        assertEquals(3, root.get("generation").getAsLong());

        JsonObject mod = root.getAsJsonArray("mods").get(0).getAsJsonObject();
        assertEquals("craftlabcore", mod.get("modId").getAsString());
        assertEquals("CraftLabCore", mod.get("name").getAsString());
        assertEquals("1.0.1", mod.get("version").getAsString());
        assertEquals("craftlabcore-1.0.1.jar", mod.get("assetName").getAsString());
        assertEquals("sha-core", mod.get("sha256").getAsString());
        assertEquals(144801L, mod.get("size").getAsLong());
        assertEquals("https://github.com/KamroniteCompany/CraftLab/releases/download/v1.0.1/craftlabcore-1.0.1.jar",
            mod.get("downloadUrl").getAsString());
    }

    @Test
    void omitsDownloadUrl_whenResolverHasNone() {
        ModPack current = new ModPack("1.21.1", "52.1.0");
        current.upsert(new ModPackEntry("craftlabcore", "CraftLabCore", "1.0.1", "GITHUB", "v1.0.1", 1L,
            "craftlabcore-1.0.1.jar", "sha-core", 144801L, ModPackEntryStatus.READY));

        String json = LauncherModPackExporter.toJson(current, modId -> null);

        JsonObject mod = GSON.fromJson(json, JsonObject.class).getAsJsonArray("mods").get(0).getAsJsonObject();
        assertFalse(mod.has("downloadUrl"), "downloadUrl doit être omis plutôt qu'un champ null explicite");
    }

    @Test
    void reflectsExactlyTheModPackGiven_neverAHiddenGlobalState() {
        // Deux ModPack distincts (l'équivalent de CURRENT et NEXT) : toJson() ne doit jamais
        // mélanger l'un avec l'autre, seul l'argument passé compte.
        ModPack current = new ModPack("1.21.1", "52.1.0");
        current.upsert(new ModPackEntry("blankmod", "BlankMod", "1.0.0", "GITHUB", "v1.0.0", 1L,
            "blankmod-1.0.0.jar", "sha-1.0.0", 2029L, ModPackEntryStatus.READY));

        ModPack next = new ModPack("1.21.1", "52.1.0");
        next.upsert(new ModPackEntry("blankmod", "BlankMod", "1.1.0", "GITHUB", "v1.1.0", 2L,
            "blankmod-1.1.0.jar", "sha-1.1.0", 2055L, ModPackEntryStatus.READY));

        String exportedFromCurrent = LauncherModPackExporter.toJson(current, modId -> null);

        JsonObject mod = GSON.fromJson(exportedFromCurrent, JsonObject.class).getAsJsonArray("mods").get(0).getAsJsonObject();
        assertEquals("1.0.0", mod.get("version").getAsString(),
            "l'export doit refléter la version de CURRENT, jamais silencieusement celle de NEXT");
        assertTrue(!exportedFromCurrent.contains("1.1.0"));
    }
}
