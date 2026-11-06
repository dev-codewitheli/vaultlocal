package com.codewitheli.vaultlocal.adapter.ui;

import com.codewitheli.vaultlocal.adapter.ui.os.AutoLock;
import com.codewitheli.vaultlocal.adapter.ui.os.OsTheme;
import com.codewitheli.vaultlocal.adapter.ui.screens.RecoveryScreen;
import com.codewitheli.vaultlocal.adapter.ui.screens.ReportScreen;
import com.codewitheli.vaultlocal.adapter.ui.screens.SettingsScreen;
import com.codewitheli.vaultlocal.adapter.ui.screens.SetupScreen;
import com.codewitheli.vaultlocal.adapter.ui.screens.UnlockScreen;
import com.codewitheli.vaultlocal.adapter.ui.screens.VaultScreen;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.Theme;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Owns the main window, swaps screens, applies the theme, tracks open dialogs, and reacts to
 * the vault locking (by any route) by wiping every screen and returning to Unlock.
 */
public final class Navigator {

    private final Stage stage;
    private final UiContext ctx;
    private final List<Stage> dialogs = new ArrayList<>();
    private Scene scene;
    private AutoLock autoLock;
    private Screen current;
    private boolean shuttingDown;

    /** A screen that can wipe its sensitive state when it is replaced or the vault locks. */
    public interface Screen {
        Parent root();

        default void dispose() {
        }
    }

    public Navigator(Stage stage, UiContext ctx) {
        this.stage = stage;
        this.ctx = ctx;
    }

    public void start() {
        scene = new Scene(new StackPane(), 1240, 720);
        applyTheme(ctx.services().settings().theme());
        stage.setScene(scene);
        stage.setTitle("VaultLocal");
        stage.setMinWidth(980);
        stage.setMinHeight(560);

        autoLock = new AutoLock(ctx.clock(), () -> ctx.services().settings().autoLockMinutes(),
                () -> lockNow(true), ctx.timings().autoLockTick(), ctx.timings().sleepGap());
        autoLock.install(scene, ctx.sessionLock());
        ctx.services().session().addLockListener(() -> Platform.runLater(this::onLocked));

        if (ctx.services().vault().vaultExists()) {
            showUnlock();
        } else {
            showSetup();
        }
        stage.show();
    }

    public UiContext context() {
        return ctx;
    }

    public Stage stage() {
        return stage;
    }

    public void showSetup() {
        show(new SetupScreen(this));
    }

    public void showUnlock() {
        show(new UnlockScreen(this));
    }

    public void showRecovery() {
        show(new RecoveryScreen(this));
    }

    public void showVault() {
        autoLock.arm();
        show(new VaultScreen(this));
    }

    public SettingsScreen showSettings() {
        SettingsScreen screen = new SettingsScreen(this);
        show(screen);
        return screen;
    }

    public void showReport() {
        show(new ReportScreen(this));
    }

    private void show(Screen screen) {
        if (current != null) {
            current.dispose();
        }
        current = screen;
        scene.setRoot(screen.root());
    }

    /** Locks immediately; {@link #onLocked()} then runs via the session listener. */
    public void lockNow(boolean automatic) {
        Runnable lock = () -> ctx.services().vault().lock(automatic);
        ctx.worker().run(lock, () -> {
        }, t -> UiErrors.log("Lock failed", t));
    }

    /**
     * Called on the FX thread when the app is closing, before the vault is locked and the database
     * closed: the lock that follows must not build an Unlock screen against a closed database.
     */
    public void shutDown() {
        shuttingDown = true;
        autoLock.disarm();
        if (current != null) {
            current.dispose();
        }
    }

    private void onLocked() {
        if (shuttingDown) {
            return;
        }
        autoLock.disarm();
        ctx.clipboard().clearIfOurs();
        for (Stage d : List.copyOf(dialogs)) {
            d.close();
        }
        dialogs.clear();
        showUnlock();
    }

    /** Creates a modal dialog window that closes automatically when the vault locks. */
    public Stage dialog(String title, Parent content) {
        Stage d = new Stage();
        d.initOwner(topWindow());
        d.initModality(Modality.WINDOW_MODAL);
        d.setTitle(title);
        Scene s = new Scene(content);
        s.getStylesheets().setAll(scene.getStylesheets());
        s.getRoot().getStyleClass().add("dialog-root");
        d.setScene(s);
        d.setResizable(false);
        s.addEventFilter(javafx.scene.input.InputEvent.ANY, e -> autoLock.touch());
        // Grow the window when an error or lockout message appears, so buttons stay in view.
        for (String selector : new String[]{".error-text", ".lockout"}) {
            for (javafx.scene.Node n : content.lookupAll(selector)) {
                n.visibleProperty().addListener((o, was, is) -> Platform.runLater(d::sizeToScene));
            }
        }
        dialogs.add(d);
        d.setOnHidden(e -> dialogs.remove(d));
        return d;
    }

    private Window topWindow() {
        return dialogs.isEmpty() ? stage : dialogs.get(dialogs.size() - 1);
    }

    public void applyTheme(Theme theme) {
        boolean dark = theme == Theme.DARK || (theme == Theme.SYSTEM && OsTheme.prefersDark());
        String base = css("base.css");
        String palette = css(dark ? "dark.css" : "light.css");
        if (scene != null) {
            scene.getStylesheets().setAll(palette, base);
        }
        for (Stage d : dialogs) {
            d.getScene().getStylesheets().setAll(palette, base);
        }
    }

    private static String css(String name) {
        return Objects.requireNonNull(Navigator.class.getResource("/vaultlocal/css/" + name), name).toExternalForm();
    }
}
