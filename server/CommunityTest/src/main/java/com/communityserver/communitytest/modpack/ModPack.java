package com.communityserver.communitytest.modpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Représente soit le ModPack CURRENT (avec lequel le serveur tourne), soit NEXT (préparé). */
public class ModPack {

    private String minecraftVersion;
    private String forgeVersion;
    private final List<ModPackEntry> mods = new ArrayList<>();
    private ModPackState state;
    private ApplyState applyState;
    private long generation;

    public ModPack() {
    }

    public ModPack(String minecraftVersion, String forgeVersion) {
        this.minecraftVersion = minecraftVersion;
        this.forgeVersion = forgeVersion;
        this.state = ModPackState.READY;
        this.applyState = ApplyState.NOT_READY;
        this.generation = 0L;
    }

    public String getMinecraftVersion() {
        return minecraftVersion;
    }

    public void setMinecraftVersion(String minecraftVersion) {
        this.minecraftVersion = minecraftVersion;
    }

    public String getForgeVersion() {
        return forgeVersion;
    }

    public void setForgeVersion(String forgeVersion) {
        this.forgeVersion = forgeVersion;
    }

    public List<ModPackEntry> getMods() {
        return mods;
    }

    public ModPackState getState() {
        return state;
    }

    public void setState(ModPackState state) {
        this.state = state;
    }

    public ApplyState getApplyState() {
        return applyState;
    }

    public void setApplyState(ApplyState applyState) {
        this.applyState = applyState;
    }

    public long getGeneration() {
        return generation;
    }

    public void setGeneration(long generation) {
        this.generation = generation;
    }

    public Optional<ModPackEntry> find(String modId) {
        return mods.stream().filter(e -> e.getModId().equals(modId)).findFirst();
    }

    /** Ajoute l'entrée, ou remplace celle du même modId si elle existait déjà (jamais de doublon). */
    public void upsert(ModPackEntry entry) {
        mods.removeIf(e -> e.getModId().equals(entry.getModId()));
        mods.add(entry);
    }

    public boolean remove(String modId) {
        return mods.removeIf(e -> e.getModId().equals(modId));
    }
}
