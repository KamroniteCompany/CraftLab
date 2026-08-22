package com.communityserver.communitytest.mod;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registre central des mods connus du serveur. Ne connaît aucun mod en particulier :
 * "communitytest" n'est qu'une entrée comme une autre, enregistrée automatiquement
 * au tout premier démarrage (voir loadOrBootstrap) pour ne pas casser le mod existant.
 */
public final class ModRegistry {

    private static final ModRegistry INSTANCE = new ModRegistry();

    public static ModRegistry get() {
        return INSTANCE;
    }

    private final ModStorage storage = new ModStorage();
    private final Map<String, ModDefinition> mods = new LinkedHashMap<>();

    private ModRegistry() {
    }

    /**
     * Charge le registre depuis le disque. Si le fichier n'existait pas encore (tout premier
     * démarrage du serveur), enregistre automatiquement CommunityTest par défaut puis sauvegarde,
     * pour que le mod existant reste utilisable sans étape manuelle. Un /mod unregister
     * communitytest ultérieur ne sera pas annulé au prochain redémarrage, puisque le fichier
     * existera alors déjà (sans cette entrée) : freshInstall ne sera plus vrai.
     */
    public synchronized void loadOrBootstrap() {
        boolean freshInstall = !storage.exists();
        mods.clear();
        mods.putAll(storage.load());

        if (freshInstall) {
            register(new ModDefinition(
                "communitytest",
                "CommunityTest",
                "OpenSourceDev",
                "1.0.0",
                "Mod de démonstration du serveur communautaire.",
                ModStatus.TESTING
            ));
        }
    }

    public synchronized boolean register(ModDefinition mod) {
        if (mods.containsKey(mod.getId())) {
            return false;
        }
        mods.put(mod.getId(), mod);
        persist();
        return true;
    }

    /**
     * Insère ou remplace inconditionnellement une entrée existante, sans jamais créer de doublon
     * (la Map est déjà indexée par modId). Utilisé pour les mises à jour légitimes, par exemple
     * un réimport GitHub d'un mod déjà connu et provenant du même repository.
     */
    public synchronized void put(ModDefinition mod) {
        mods.put(mod.getId(), mod);
        persist();
    }

    public synchronized boolean unregister(String modId) {
        boolean removed = mods.remove(modId) != null;
        if (removed) {
            persist();
        }
        return removed;
    }

    public synchronized Optional<ModDefinition> get(String modId) {
        return Optional.ofNullable(mods.get(modId));
    }

    public synchronized boolean exists(String modId) {
        return mods.containsKey(modId);
    }

    public synchronized Collection<ModDefinition> getAll() {
        return new ArrayList<>(mods.values());
    }

    /** Reflète le statut d'une proposition démarrée/résolue sur la fiche du mod correspondant. */
    public synchronized void updateStatus(String modId, ModStatus status) {
        ModDefinition mod = mods.get(modId);
        if (mod != null) {
            mod.setStatus(status);
            persist();
        }
    }

    private void persist() {
        storage.save(mods.values());
    }
}
