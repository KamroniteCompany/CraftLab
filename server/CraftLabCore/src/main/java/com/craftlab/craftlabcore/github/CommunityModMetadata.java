package com.craftlab.craftlabcore.github;

/** Contenu attendu du fichier community-mod.json à la racine du repository du développeur. */
public record CommunityModMetadata(
    String modId,
    String name,
    String author,
    String description,
    String minecraftVersion,
    String loader
) {
}
