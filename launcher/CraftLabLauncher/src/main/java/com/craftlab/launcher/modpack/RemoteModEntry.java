package com.craftlab.launcher.modpack;

/** Reflète une entrée de current-modpack-launcher.json (voir LauncherModPackExporter côté serveur). */
public record RemoteModEntry(
    String modId,
    String name,
    String version,
    String assetName,
    String sha256,
    long size,
    String downloadUrl
) {
}
