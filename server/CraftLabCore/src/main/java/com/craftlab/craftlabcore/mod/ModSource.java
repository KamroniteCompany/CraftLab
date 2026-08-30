package com.craftlab.craftlabcore.mod;

/**
 * Origine d'un mod enregistré. Pour cette version, seule GITHUB est produite automatiquement
 * (par GitHubModImporter) ; un mod enregistré manuellement via /mod register n'a pas de source
 * (source == null sur son ModDefinition).
 */
public class ModSource {

    public static final String TYPE_GITHUB = "GITHUB";

    private String type;
    private String owner;
    private String repository;
    private String repositoryUrl;

    public ModSource() {
    }

    public ModSource(String type, String owner, String repository, String repositoryUrl) {
        this.type = type;
        this.owner = owner;
        this.repository = repository;
        this.repositoryUrl = repositoryUrl;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getRepository() {
        return repository;
    }

    public void setRepository(String repository) {
        this.repository = repository;
    }

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    public void setRepositoryUrl(String repositoryUrl) {
        this.repositoryUrl = repositoryUrl;
    }
}
