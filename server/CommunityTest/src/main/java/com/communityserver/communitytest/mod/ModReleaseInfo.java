package com.communityserver.communitytest.mod;

/** Dernière release GitHub importée pour ce mod. Null si le mod n'a pas de source GitHub. */
public class ModReleaseInfo {

    private String tag;
    private long releaseId;
    private String assetName;
    private String assetDownloadUrl;

    public ModReleaseInfo() {
    }

    public ModReleaseInfo(String tag, long releaseId, String assetName, String assetDownloadUrl) {
        this.tag = tag;
        this.releaseId = releaseId;
        this.assetName = assetName;
        this.assetDownloadUrl = assetDownloadUrl;
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
}
