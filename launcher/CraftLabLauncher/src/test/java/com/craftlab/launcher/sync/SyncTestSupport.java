package com.craftlab.launcher.sync;

import com.craftlab.launcher.state.LauncherState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Helpers partagés entre les tests de synchronisation (extraits des tests SyncManager existants). */
final class SyncTestSupport {

    private SyncTestSupport() {
    }

    static void writeFile(Path path, byte[] content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, content);
    }

    static void writeFile(Path path, String content) throws IOException {
        writeFile(path, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String sha256(byte[] content) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return sha256(Files.readAllBytes(file));
    }

    static SyncManager.Listener noopListener() {
        return new SyncManager.Listener() {
            @Override
            public void onStateChanged(LauncherState state) {
            }

            @Override
            public void onModProgress(String modId, long bytesWritten, long totalBytesHint) {
            }

            @Override
            public void onLog(String message) {
            }
        };
    }

    static <T> T await(CompletableFuture<T> future) throws ExecutionException, InterruptedException, TimeoutException {
        return future.get(10, TimeUnit.SECONDS);
    }
}
