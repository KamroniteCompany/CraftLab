package com.communityserver.communitytest.modpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Liste des fichiers du dossier /mods de Forge dont CraftLab est responsable. Tout fichier
 * de /mods absent de ce manifest (JEI, outils serveur tiers, etc.) n'est jamais touché par
 * ModPackApplier, qu'il s'agisse d'un ajout, d'une mise à jour, d'une suppression ou d'un
 * rollback.
 */
public class ManagedModsManifest {

    private final List<ManagedModEntry> mods = new ArrayList<>();

    public List<ManagedModEntry> getMods() {
        return mods;
    }

    public Optional<ManagedModEntry> find(String modId) {
        return mods.stream().filter(e -> e.getModId().equals(modId)).findFirst();
    }

    public boolean isManagedFile(String fileName) {
        return mods.stream().anyMatch(e -> e.getFile().equals(fileName));
    }
}
