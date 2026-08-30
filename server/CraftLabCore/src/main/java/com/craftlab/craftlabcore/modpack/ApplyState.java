package com.craftlab.craftlabcore.modpack;

/**
 * État du cycle d'application d'un ModPack vers le dossier /mods réel de Forge — distinct de
 * ModPackState, qui décrit uniquement si les fichiers d'un ModPack sont téléchargés et vérifiés.
 * ApplyState décrit si ce ModPack a été effectivement déployé sur le disque.
 */
public enum ApplyState {
    NOT_READY,
    READY,
    APPLYING,
    APPLIED,
    FAILED
}
