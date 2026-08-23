package com.communityserver.communitytest.modpack;

/**
 * Statut d'une entrée de ModPack. Par construction, une entrée n'est ajoutée à un ModPack
 * qu'une fois entièrement téléchargée et vérifiée (voir ModPackManager) : READY est donc la
 * seule valeur jamais persistée aujourd'hui. FAILED existe pour représenter explicitement
 * l'échec d'une préparation, sans pour autant ajouter l'entrée au ModPack (voir §24).
 */
public enum ModPackEntryStatus {
    READY,
    FAILED
}
