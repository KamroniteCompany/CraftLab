package com.craftlab.launcher.sync;

import com.craftlab.launcher.instance.InstalledModEntry;
import com.craftlab.launcher.instance.InstalledModPack;
import com.craftlab.launcher.modpack.RemoteModEntry;
import com.craftlab.launcher.modpack.RemoteModPack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Compare le ModPack distant à l'installation locale enregistrée. Ne fait jamais confiance
 * uniquement au nom du fichier ou à la version : un mod n'est considéré à jour que si sa
 * version ET son SHA-256 enregistré correspondent exactement à ceux du ModPack distant.
 */
public final class ModPackComparator {

    private ModPackComparator() {
    }

    public static SyncPlan compare(RemoteModPack remote, InstalledModPack local) {
        List<RemoteModEntry> toDownload = new ArrayList<>();
        List<RemoteModEntry> upToDate = new ArrayList<>();

        for (RemoteModEntry entry : remote.mods()) {
            Optional<InstalledModEntry> installed = find(local, entry.modId());
            boolean matches = installed.isPresent()
                && installed.get().version().equals(entry.version())
                && installed.get().sha256() != null
                && installed.get().sha256().equalsIgnoreCase(entry.sha256());
            if (matches) {
                upToDate.add(entry);
            } else {
                toDownload.add(entry);
            }
        }

        List<InstalledModEntry> toRemove = new ArrayList<>();
        if (local != null) {
            for (InstalledModEntry installedEntry : local.mods()) {
                boolean stillNeeded = remote.mods().stream().anyMatch(e -> e.modId().equals(installedEntry.modId()));
                if (!stillNeeded) {
                    toRemove.add(installedEntry);
                }
            }
        }

        return new SyncPlan(toDownload, toRemove, upToDate);
    }

    private static Optional<InstalledModEntry> find(InstalledModPack local, String modId) {
        if (local == null) {
            return Optional.empty();
        }
        return local.mods().stream().filter(e -> e.modId().equals(modId)).findFirst();
    }
}
