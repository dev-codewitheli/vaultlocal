package com.codewitheli.vaultlocal.app;

import com.codewitheli.vaultlocal.adapter.crypto.AesGcmAead;
import com.codewitheli.vaultlocal.adapter.crypto.Argon2idKdf;
import com.codewitheli.vaultlocal.adapter.crypto.SecureRandomSource;
import com.codewitheli.vaultlocal.adapter.persistence.SqliteDatabase;
import com.codewitheli.vaultlocal.adapter.persistence.SqliteRepositories;
import com.codewitheli.vaultlocal.adapter.strength.LocalStrengthEstimator;
import com.codewitheli.vaultlocal.adapter.ui.UiContext;
import com.codewitheli.vaultlocal.adapter.ui.UiErrors;
import com.codewitheli.vaultlocal.adapter.ui.VaultApp;
import com.codewitheli.vaultlocal.adapter.ui.Worker;
import com.codewitheli.vaultlocal.adapter.ui.os.ClipboardService;
import com.codewitheli.vaultlocal.adapter.ui.os.DesktopSessionLockSource;
import com.codewitheli.vaultlocal.adapter.ui.os.SessionLockSource;
import com.codewitheli.vaultlocal.adapter.ui.os.WindowsSessionLockSource;
import com.codewitheli.vaultlocal.application.VaultServices;
import com.codewitheli.vaultlocal.application.keys.KdfProfile;
import com.codewitheli.vaultlocal.application.port.out.ClockPort;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Composition root: resolves the data directory, configures local-only logging, takes the
 * single-instance lock, opens the vault file, wires the hexagon, and starts JavaFX.
 * Not a JavaFX {@code Application} subclass, so it also starts from a plain classpath.
 */
public final class Main {

    private static final System.Logger LOG = System.getLogger("vaultlocal");

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path home = AppPaths.ensure(AppPaths.dataDir());
        Path nativeDir = AppPaths.ensure(home.resolve("native"));
        // Native helper libraries (SQLite, JNA) are unpacked here instead of the shared temp dir.
        System.setProperty("org.sqlite.tmpdir", nativeDir.toString());
        System.setProperty("jna.tmpdir", nativeDir.toString());
        System.setProperty("javafx.cachedir", nativeDir.toString());
        Logging.configure(AppPaths.ensure(home.resolve("logs")));
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> UiErrors.log("Uncaught on " + t.getName(), e));

        Optional<SingleInstance> instance = SingleInstance.acquire(home.resolve("vault.lock"));
        if (instance.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "Another VaultLocal instance is already running");
            AlreadyRunning.show(args);
            return;
        }
        LOG.log(System.Logger.Level.INFO, "VaultLocal starting");

        SqliteDatabase db = SqliteDatabase.open(home.resolve("vault.db"));
        SqliteRepositories repos = new SqliteRepositories(db);
        SecureRandomSource random = new SecureRandomSource();
        VaultServices services = VaultServices.create(new VaultServices.Ports(
                new Argon2idKdf(), new AesGcmAead(random), random, new LocalStrengthEstimator(), ClockPort.SYSTEM, db,
                repos.vaultMeta(), repos.keyWraps(), repos.securityQuestions(), repos.entries(),
                repos.passwordHistory(), repos.lockouts(), repos.activityLog(), repos.settings()),
                KdfProfile.PRODUCTION);

        UiContext.Timings timings = UiContext.Timings.DEFAULT;
        UiContext ctx = new UiContext(services, new Worker(), new ClipboardService(timings.clipboardClear()),
                ClockPort.SYSTEM, sessionLockSource(), timings);
        VaultApp.launch(ctx, () -> {
            db.close();
            instance.get().close();
            LOG.log(System.Logger.Level.INFO, "VaultLocal stopped");
        }, args);
    }

    private static SessionLockSource sessionLockSource() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? new WindowsSessionLockSource() : new DesktopSessionLockSource();
    }
}
