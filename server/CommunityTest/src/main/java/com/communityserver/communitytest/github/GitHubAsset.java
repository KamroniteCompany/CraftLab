package com.communityserver.communitytest.github;

/** Un fichier attaché à une GitHub Release. */
public record GitHubAsset(
    String name,
    long sizeBytes,
    String contentType,
    String downloadUrl
) {
}
