package com.craftlab.launcher.modpack;

import java.util.List;

public record RemoteModPack(String minecraftVersion, String forgeVersion, long generation, List<RemoteModEntry> mods) {
}
