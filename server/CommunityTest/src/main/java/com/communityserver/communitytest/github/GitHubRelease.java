package com.communityserver.communitytest.github;

import java.util.List;
import java.util.Locale;

/** Une GitHub Release et ses assets. */
public record GitHubRelease(
    long id,
    String tag,
    String name,
    String body,
    String publishedAt,
    boolean draft,
    boolean prerelease,
    String htmlUrl,
    List<GitHubAsset> assets
) {

    /** Assets dont le nom se termine par .jar et dont la taille est strictement positive. */
    public List<GitHubAsset> jarAssets() {
        return assets.stream()
            .filter(asset -> asset.name().toLowerCase(Locale.ROOT).endsWith(".jar"))
            .filter(asset -> asset.sizeBytes() > 0)
            .toList();
    }
}
