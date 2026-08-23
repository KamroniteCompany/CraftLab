package com.communityserver.communitytest.modpack;

import java.nio.file.Path;
import java.time.Instant;

/** Référence vers un instantané des fichiers /mods gérés par CraftLab, pris avant une application. */
public record ModPackBackup(String id, Instant createdAt, Path folder) {
}
