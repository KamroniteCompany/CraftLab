package com.craftlab.craftlabcore.modpack;

/** Une entrée du manifest des fichiers de /mods dont CraftLab est responsable. */
public class ManagedModEntry {

    private String modId;
    private String file;
    private String sha256;

    public ManagedModEntry() {
    }

    public ManagedModEntry(String modId, String file, String sha256) {
        this.modId = modId;
        this.file = file;
        this.sha256 = sha256;
    }

    public String getModId() {
        return modId;
    }

    public void setModId(String modId) {
        this.modId = modId;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }
}
