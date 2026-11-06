package com.codewitheli.vaultlocal.adapter.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.InputStream;
import java.util.Objects;

/** JavaFX entry point. The composition root hands over a {@link UiContext} before launch. */
public final class VaultApp extends Application {

    private static UiContext context;
    private Navigator navigator;
    private static Runnable onExit = () -> {
    };

    public static void launch(UiContext ctx, Runnable exitHook, String[] args) {
        context = Objects.requireNonNull(ctx);
        onExit = exitHook;
        Application.launch(VaultApp.class, args);
    }

    @Override
    public void start(Stage stage) {
        Thread.currentThread().setUncaughtExceptionHandler((t, e) -> UiErrors.log("Uncaught on FX thread", e));
        try (InputStream icon = VaultApp.class.getResourceAsStream("/vaultlocal/icon.png")) {
            if (icon != null) {
                stage.getIcons().add(new Image(icon));
            }
        } catch (java.io.IOException ignored) {
            // the window just keeps the default icon
        }
        Platform.setImplicitExit(true);
        navigator = new Navigator(stage, context);
        navigator.start();
    }

    @Override
    public void stop() {
        if (navigator != null) {
            navigator.shutDown();
        }
        context.clipboard().clearIfOurs();
        context.services().vault().lock(false);
        context.sessionLock().close();
        context.worker().close();
        onExit.run();
    }
}
