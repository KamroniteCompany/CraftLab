package com.craftlab.launcher.instance;

import java.util.List;

public record InstalledModPack(String minecraftVersion, String forgeVersion, long generation, List<InstalledModEntry> mods) {
}
