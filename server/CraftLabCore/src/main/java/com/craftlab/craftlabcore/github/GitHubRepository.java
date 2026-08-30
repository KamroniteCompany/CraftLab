package com.craftlab.craftlabcore.github;

/** Métadonnées d'un repository GitHub, telles que retournées par l'API publique. */
public record GitHubRepository(
    String owner,
    String name,
    String htmlUrl,
    String description,
    String homepage,
    int stars,
    String updatedAt
) {
}
