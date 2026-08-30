package com.craftlab.launcher.instance;

public record InstalledModEntry(String modId, String version, String assetName, String sha256, long size) {
}
