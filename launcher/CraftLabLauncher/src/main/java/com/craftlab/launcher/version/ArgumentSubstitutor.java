package com.craftlab.launcher.version;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Résout les ${...} des listes d'arguments JVM/jeu issues du version JSON. */
public final class ArgumentSubstitutor {

    private ArgumentSubstitutor() {
    }

    public static List<String> substitute(List<String> raw, Map<String, String> values) {
        return raw.stream()
            .map(arg -> substituteOne(arg, values))
            .collect(Collectors.toList());
    }

    private static String substituteOne(String arg, Map<String, String> values) {
        String result = arg;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}
