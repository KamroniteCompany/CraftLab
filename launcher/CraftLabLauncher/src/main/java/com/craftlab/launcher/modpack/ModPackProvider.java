package com.craftlab.launcher.modpack;

import java.util.concurrent.CompletableFuture;

/**
 * Abstraction de la source du ModPack officiel CraftLab. Pour cette première version, une
 * simple URL HTTP(S) ou un fichier local (HttpModPackProvider / LocalFileModPackProvider).
 * Remplaçable plus tard par un vrai client d'API CraftLab sans changer le reste du launcher :
 * seule une nouvelle implémentation de cette interface serait nécessaire.
 */
public interface ModPackProvider {
    CompletableFuture<RemoteModPack> getCurrentModPack();
}
