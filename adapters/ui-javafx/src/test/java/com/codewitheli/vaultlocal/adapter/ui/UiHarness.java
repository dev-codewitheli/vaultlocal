package com.codewitheli.vaultlocal.adapter.ui;

import com.codewitheli.vaultlocal.adapter.crypto.AesGcmAead;
import com.codewitheli.vaultlocal.adapter.crypto.Argon2idKdf;
import com.codewitheli.vaultlocal.adapter.crypto.SecureRandomSource;
import com.codewitheli.vaultlocal.adapter.persistence.SqliteDatabase;
import com.codewitheli.vaultlocal.adapter.persistence.SqliteRepositories;
import com.codewitheli.vaultlocal.adapter.ui.os.ClipboardService;
import com.codewitheli.vaultlocal.adapter.ui.os.SessionLockSource;
import com.codewitheli.vaultlocal.application.VaultServices;
import com.codewitheli.vaultlocal.application.keys.KdfProfile;
import com.codewitheli.vaultlocal.application.port.out.ClockPort;
import com.codewitheli.vaultlocal.application.port.out.PasswordStrengthPort;
import com.codewitheli.vaultlocal.domain.entry.EntryDetails;
import com.codewitheli.vaultlocal.domain.entry.Platform;
import com.codewitheli.vaultlocal.domain.entry.PlatformKind;
import com.codewitheli.vaultlocal.domain.recovery.PredefinedQuestion;
import com.codewitheli.vaultlocal.domain.recovery.SecurityQuestion;
import com.codewitheli.vaultlocal.domain.secret.Secret;

import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** A real vault (SQLite + Argon2id with test costs + AES-GCM) behind the real UI. */
final class UiHarness implements AutoCloseable {

    static final String MASTER = "Correct-Horse-42-Battery";
    static final String[] ANSWERS = {"Fluffy", "Metallica", "Toyota Corolla"};
    static final List<SecurityQuestion> QUESTIONS = List.of(
            SecurityQuestion.predefined(PredefinedQuestion.FIRST_PET),
            SecurityQuestion.predefined(PredefinedQuestion.FIRST_CONCERT),
            SecurityQuestion.predefined(PredefinedQuestion.FIRST_CAR));
    static final UiContext.Timings FAST = new UiContext.Timings(Duration.ofSeconds(2), Duration.ofSeconds(2),
            Duration.ofSeconds(1), Duration.ofSeconds(30));

    final SqliteDatabase db;
    final VaultServices services;
    final UiContext ctx;

    UiHarness(Path dir) {
        this(dir, ClockPort.SYSTEM, new LengthStrength());
    }

    UiHarness(Path dir, ClockPort clock, PasswordStrengthPort strength) {
        db = SqliteDatabase.open(dir.resolve("vault.db"));
        SqliteRepositories repos = new SqliteRepositories(db);
        SecureRandomSource random = new SecureRandomSource();
        services = VaultServices.create(new VaultServices.Ports(new Argon2idKdf(), new AesGcmAead(random), random,
                strength, clock, db, repos.vaultMeta(), repos.keyWraps(),
                repos.securityQuestions(), repos.entries(), repos.passwordHistory(), repos.lockouts(),
                repos.activityLog(), repos.settings()), KdfProfile.FOR_TESTS);
        ctx = new UiContext(services, new Worker(), new ClipboardService(FAST.clipboardClear()), clock,
                SessionLockSource.NONE, FAST);
    }

    /** Creates the vault directly (bypassing the setup UI) and locks it, as after a restart. */
    UiHarness withVault(String... entries) {
        try (Secret mk = s(MASTER); Secret a = s(ANSWERS[0]); Secret b = s(ANSWERS[1]); Secret c = s(ANSWERS[2])) {
            services.vault().createVault(mk, true, QUESTIONS, List.of(a, b, c));
        }
        for (String label : entries) {
            try (Secret pw = s("pw-of-" + label + "-X9!")) {
                services.entries().addEntry(new EntryDetails(Platform.of(PlatformKind.GMAIL), label,
                        label.toLowerCase() + "@example.com", null, null), pw, false);
            }
        }
        services.vault().lock(false);
        return this;
    }

    static Secret s(String text) {
        return Secret.wrap(text.toCharArray());
    }

    /** Saves a PNG of the window's scene to build/screenshots for visual review. */
    static void screenshot(Window window, String name) {
        try {
            CompletableFuture<WritableImage> f = new CompletableFuture<>();
            javafx.application.Platform.runLater(() -> {
                Scene scene = window.getScene();
                f.complete(scene.snapshot(null));
            });
            WritableImage img = f.get(10, TimeUnit.SECONDS);
            Path out = Path.of("build", "screenshots");
            Files.createDirectories(out);
            ImageIO.write(toBuffered(img), "png", out.resolve(name + ".png").toFile());
        } catch (Exception e) {
            throw new IllegalStateException("screenshot failed", e);
        }
    }

    private static BufferedImage toBuffered(WritableImage img) {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        return out;
    }

    @Override
    public void close() throws IOException {
        // Close windows and let the lock listener's FX work finish before the DB goes away.
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
        javafx.application.Platform.runLater(() -> javafx.stage.Window.getWindows().stream().toList()
                .forEach(w -> ((javafx.stage.Stage) w).close()));
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
        services.session().lock();
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
        ctx.worker().close();
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
        db.close();
    }

    /** Length-based stand-in for the strength estimator. */
    static final class LengthStrength implements PasswordStrengthPort {
        @Override
        public StrengthResult estimate(Secret password) {
            long distinct = new String(password.chars()).chars().distinct().count();
            int score = distinct <= 3 ? 0 : password.length() < 12 ? 1 : 3;
            return new StrengthResult(score, "", List.of());
        }
    }
}
