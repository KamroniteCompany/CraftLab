package com.craftlab.launcher.auth.microsoft;

/** id : UUID SANS tirets, exactement le format attendu par ${auth_uuid} (voir MinecraftLauncher / OfflineAuthProvider). */
record MinecraftProfile(String id, String name) {
}
