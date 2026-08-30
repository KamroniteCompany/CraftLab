package com.craftlab.launcher.version;

import java.util.List;

public record ResolvedVersion(
    String id,
    String mainClass,
    String assetsId,
    List<String> classpathEntries,
    List<String> gameArguments,
    List<String> jvmArguments
) {
}
