package com.craftlab.launcher.sync;

import com.craftlab.launcher.instance.InstalledModEntry;
import com.craftlab.launcher.modpack.RemoteModEntry;

import java.util.List;

public record SyncPlan(List<RemoteModEntry> toDownload, List<InstalledModEntry> toRemove, List<RemoteModEntry> upToDate) {
    public boolean isEmpty() {
        return toDownload.isEmpty() && toRemove.isEmpty();
    }
}
