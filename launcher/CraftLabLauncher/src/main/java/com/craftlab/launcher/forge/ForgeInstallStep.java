package com.craftlab.launcher.forge;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Une étape ("processor") de install_profile.json : un outil Java à exécuter avec des
 * arguments à résoudre. Pure donnée, ne s'exécute pas elle-même (voir ForgeInstaller).
 */
public final class ForgeInstallStep {

    private final String jarCoordinates;
    private final List<String> classpathCoordinates;
    private final List<String> rawArgs;
    private final Map<String, String> rawOutputs;
    private final boolean appliesToClient;

    private ForgeInstallStep(String jarCoordinates, List<String> classpathCoordinates, List<String> rawArgs,
                              Map<String, String> rawOutputs, boolean appliesToClient) {
        this.jarCoordinates = jarCoordinates;
        this.classpathCoordinates = classpathCoordinates;
        this.rawArgs = rawArgs;
        this.rawOutputs = rawOutputs;
        this.appliesToClient = appliesToClient;
    }

    static ForgeInstallStep parse(JsonObject processor) {
        String jarCoordinates = processor.get("jar").getAsString();

        List<String> classpath = new ArrayList<>();
        if (processor.has("classpath")) {
            processor.getAsJsonArray("classpath").forEach(e -> classpath.add(e.getAsString()));
        }

        List<String> args = new ArrayList<>();
        if (processor.has("args")) {
            processor.getAsJsonArray("args").forEach(e -> args.add(e.getAsString()));
        }

        Map<String, String> outputs = new LinkedHashMap<>();
        if (processor.has("outputs")) {
            for (var entry : processor.getAsJsonObject("outputs").entrySet()) {
                outputs.put(entry.getKey(), entry.getValue().isJsonNull() ? null : entry.getValue().getAsString());
            }
        }

        // Si "sides" est absent, l'étape s'applique aux deux côtés (donc au client).
        boolean appliesToClient = true;
        if (processor.has("sides")) {
            appliesToClient = false;
            for (var side : processor.getAsJsonArray("sides")) {
                if (side.getAsString().equalsIgnoreCase("client")) {
                    appliesToClient = true;
                }
            }
        }

        return new ForgeInstallStep(jarCoordinates, classpath, args, outputs, appliesToClient);
    }

    public String jarCoordinates() {
        return jarCoordinates;
    }

    public List<String> classpathCoordinates() {
        return classpathCoordinates;
    }

    public List<String> rawArgs() {
        return rawArgs;
    }

    public Map<String, String> rawOutputs() {
        return rawOutputs;
    }

    public boolean appliesToClient() {
        return appliesToClient;
    }
}
