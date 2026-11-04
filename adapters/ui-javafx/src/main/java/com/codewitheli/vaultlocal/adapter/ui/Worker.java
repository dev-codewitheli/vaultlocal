package com.codewitheli.vaultlocal.adapter.ui;

import javafx.application.Platform;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Runs vault operations (Argon2id, SQLite) off the JavaFX thread, one at a time, and delivers
 * the outcome back on the JavaFX thread.
 */
public final class Worker implements AutoCloseable {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vault-worker");
        t.setDaemon(true);
        return t;
    });

    public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        executor.execute(() -> {
            try {
                T result = work.call();
                Platform.runLater(() -> onSuccess.accept(result));
            } catch (Throwable t) {
                Platform.runLater(() -> onFailure.accept(t));
            }
        });
    }

    public void run(Runnable work, Runnable onSuccess, Consumer<Throwable> onFailure) {
        run(() -> {
            work.run();
            return null;
        }, ignored -> onSuccess.run(), onFailure);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
