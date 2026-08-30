package com.craftlab.launcher.sync;

import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * Écrit le corps HTTP dans un fichier temporaire, interrompt le flux dès dépassement de
 * maxBytes (même stratégie que ModDownloadManager côté serveur), et notifie la progression
 * au fur et à mesure pour l'affichage dans l'interface.
 */
final class ProgressLimitedBodySubscriber implements HttpResponse.BodySubscriber<Path> {

    private final Path targetPath;
    private final long maxBytes;
    private final LongConsumer onBytesWritten;
    private final CompletableFuture<Path> result = new CompletableFuture<>();
    private final AtomicLong written = new AtomicLong(0);
    private volatile OutputStream out;
    private volatile Flow.Subscription subscription;

    ProgressLimitedBodySubscriber(Path targetPath, long maxBytes, LongConsumer onBytesWritten) {
        this.targetPath = targetPath;
        this.maxBytes = maxBytes;
        this.onBytesWritten = onBytesWritten;
    }

    @Override
    public CompletionStage<Path> getBody() {
        return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        try {
            out = Files.newOutputStream(targetPath,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            subscription.cancel();
            result.completeExceptionally(e);
            return;
        }
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
        try {
            for (ByteBuffer buffer : items) {
                int remaining = buffer.remaining();
                long total = written.addAndGet(remaining);
                if (total > maxBytes) {
                    closeQuietly();
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Fichier trop volumineux (> " + maxBytes + " octets)."));
                    return;
                }
                byte[] bytes = new byte[remaining];
                buffer.get(bytes);
                out.write(bytes);
                onBytesWritten.accept(total);
            }
        } catch (IOException e) {
            closeQuietly();
            subscription.cancel();
            result.completeExceptionally(e);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        closeQuietly();
        result.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
        try {
            if (out != null) {
                out.close();
            }
            result.complete(targetPath);
        } catch (IOException e) {
            result.completeExceptionally(e);
        }
    }

    private void closeQuietly() {
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException ignored) {
            // Le fichier .part résiduel sera nettoyé par l'appelant en cas d'échec.
        }
    }
}
