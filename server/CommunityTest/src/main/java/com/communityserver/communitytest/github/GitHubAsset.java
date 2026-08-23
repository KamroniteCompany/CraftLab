package com.communityserver.communitytest.github;

/**
 * Un fichier attaché à une GitHub Release. sha256Digest est renseigné uniquement si GitHub
 * expose un champ "digest" au format "sha256:..." pour cet asset ; sinon null, auquel cas
 * ModDownloadManager calculera et retiendra lui-même le SHA-256 après téléchargement.
 */
public record GitHubAsset(
    String name,
    long sizeBytes,
    String contentType,
    String downloadUrl,
    String sha256Digest
) {
}
