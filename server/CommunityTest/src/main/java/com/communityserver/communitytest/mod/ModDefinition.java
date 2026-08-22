package com.communityserver.communitytest.mod;

/** Représente un mod connu du serveur, indépendamment de tout vote en cours ou passé. */
public class ModDefinition {

    private String id;
    private String name;
    private String author;
    private String version;
    private String description;
    private ModStatus status;

    /** Renseigné uniquement si ce mod provient d'un import GitHub ; null pour un /mod register manuel. */
    private ModSource source;

    /** Renseigné uniquement si ce mod provient d'un import GitHub. */
    private ModReleaseInfo release;

    /** Constructeur requis pour la désérialisation depuis ModStorage. */
    public ModDefinition() {
    }

    public ModDefinition(String id, String name, String author, String version, String description, ModStatus status) {
        this.id = id;
        this.name = name;
        this.author = author;
        this.version = version;
        this.description = description;
        this.status = status;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ModStatus getStatus() {
        return status;
    }

    public void setStatus(ModStatus status) {
        this.status = status;
    }

    public ModSource getSource() {
        return source;
    }

    public void setSource(ModSource source) {
        this.source = source;
    }

    public ModReleaseInfo getRelease() {
        return release;
    }

    public void setRelease(ModReleaseInfo release) {
        this.release = release;
    }
}
