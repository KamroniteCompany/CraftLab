package com.craftlab.launcher.log;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/** Journal local (logs/launcher-<horodatage>.log) permettant de diagnostiquer chaque étape. */
public class LauncherLog {

    private final Path filePath;
    private PrintWriter writer;
    private Consumer<String> uiListener = line -> { };

    public LauncherLog(Path logsDir) {
        String name = "launcher-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) + ".log";
        this.filePath = logsDir.resolve(name);
        try {
            Files.createDirectories(logsDir);
            writer = new PrintWriter(Files.newBufferedWriter(filePath), true);
        } catch (IOException e) {
            writer = null;
        }
    }

    public void setUiListener(Consumer<String> listener) {
        this.uiListener = listener != null ? listener : (line -> { });
    }

    public synchronized void log(String message) {
        String line = "[" + DateTimeFormatter.ofPattern("HH:mm:ss").format(LocalDateTime.now()) + "] " + message;
        if (writer != null) {
            writer.println(line);
        }
        uiListener.accept(line);
    }

    public Path filePath() {
        return filePath;
    }
}
