package com.communityserver.communitytest.modpack;

/** Une version exacte et reproductible d'un mod au sein d'un ModPack (CURRENT ou NEXT). */
public class ModPackEntry {

    private String modId;
    private String name;
    private String version;
    private String source;
    private String releaseTag;
    private long releaseId;
    private String assetName;
    private String sha256;
    private long size;
    private ModPackEntryStatus status;

    public ModPackEntry() {
    }

    public ModPackEntry(String modId, String name, String version, String source, String releaseTag,
                         long releaseId, String assetName, String sha256, long size, ModPackEntryStatus status) {
        this.modId = modId;
        this.name = name;
        this.version = version;
        this.source = source;
        this.releaseTag = releaseTag;
        this.releaseId = releaseId;
        this.assetName = assetName;
        this.sha256 = sha256;
        this.size = size;
        this.status = status;
    }

    public String getModId() {
        return modId;
    }

    public void setModId(String modId) {
        this.modId = modId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getReleaseTag() {
        return releaseTag;
    }

    public void setReleaseTag(String releaseTag) {
        this.releaseTag = releaseTag;
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

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public ModPackEntryStatus getStatus() {
        return status;
    }

    public void setStatus(ModPackEntryStatus status) {
        this.status = status;
    }
}
