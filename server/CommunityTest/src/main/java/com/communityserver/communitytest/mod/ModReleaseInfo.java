package com.communityserver.communitytest.mod;

/**
 * Dernière release GitHub importée pour ce mod. Null si le mod n'a pas de source GitHub.
 * expectedSha256 provient du champ "digest" de l'asset GitHub s'il était disponible au moment
 * de l'import ; sinon null, auquel cas ModDownloadManager calcule et retient lui-même le
 * SHA-256 après le premier téléchargement réussi.
 */
public class ModReleaseInfo {

    private String tag;
    private long releaseId;
    private String assetName;
    private String assetDownloadUrl;
    private String expectedSha256;

    public ModReleaseInfo() {
    }

    public ModReleaseInfo(String tag, long releaseId, String assetName, String assetDownloadUrl, String expectedSha256) {
        this.tag = tag;
        this.releaseId = releaseId;
        this.assetName = assetName;
        this.assetDownloadUrl = assetDownloadUrl;
        this.expectedSha256 = expectedSha256;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public long getReleaseId() {
        return releaseId;
    }

    public void setReleaseId(long releaseId) {
        this.releaseId = releaseId;
    }

    public String getAssetName() {
        return assetName;
    }

    public void setAssetName(String assetName) {
        this.assetName = assetName;
    }

    public String getAssetDownloadUrl() {
        return assetDownloadUrl;
    }

    public void setAssetDownloadUrl(String assetDownloadUrl) {
        this.assetDownloadUrl = assetDownloadUrl;
    }

    public String getExpectedSha256() {
        return expectedSha256;
    }

    public void setExpectedSha256(String expectedSha256) {
        this.expectedSha256 = expectedSha256;
    }
}
