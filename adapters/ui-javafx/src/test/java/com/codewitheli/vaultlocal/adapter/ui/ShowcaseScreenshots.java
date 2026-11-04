package com.codewitheli.vaultlocal.adapter.ui;

import com.codewitheli.vaultlocal.adapter.ui.screens.EntryRow;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.Theme;
import com.codewitheli.vaultlocal.application.port.out.ClockPort;
import com.codewitheli.vaultlocal.application.port.out.PasswordStrengthPort;
import com.codewitheli.vaultlocal.domain.entry.EntryDetails;
import com.codewitheli.vaultlocal.domain.entry.Platform;
import com.codewitheli.vaultlocal.domain.entry.PlatformKind;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.util.WaitForAsyncUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Renders README / portfolio screenshots with made-up demo data (no real accounts). Run with
 * {@code ./gradlew :adapters:ui-javafx:screenshots}; output goes to {@code docs/screenshots}.
 */
@Tag("screenshots")
@ExtendWith(ApplicationExtension.class)
class ShowcaseScreenshots {

    @TempDir
    Path dir;

    @Test
    void render(FxRobot robot) throws Exception {
        Path out = Path.of(System.getProperty("screenshots.dir", "build/showcase"));
        Files.createDirectories(out);
        MovableClock clock = new MovableClock();
        UiHarness h = new UiHarness(dir, clock, new DemoStrength());
        h.services.settings().setTheme(Theme.DARK);

        Stage[] stage = new Stage[1];
        robot.interact(() -> {
            stage[0] = new Stage();
            new Navigator(stage[0], h.ctx).start();
            stage[0].setWidth(1200);
            stage[0].setHeight(750);
        });
        WaitForAsyncUtils.waitForFxEvents();
        robot.clickOn("#setupStart");
        robot.clickOn("#mkKey").write("violet anchor brisk tundra 42");
        robot.clickOn("#mkConfirm").write("violet anchor brisk tundra 42");
        save(stage[0], out.resolve("setup-master-key.png"));

        // Seed the demo vault directly; one entry is backdated so it shows as stale.
        try (Secret mk = UiHarness.s("violet anchor brisk tundra 42"); Secret a = UiHarness.s("Mochi");
             Secret b = UiHarness.s("Eraserheads"); Secret c = UiHarness.s("Toyota Corolla")) {
            h.services.vault().createVault(mk, true, UiHarness.QUESTIONS, List.of(a, b, c));
        }
        clock.shift(Duration.ofDays(-140));
        add(h, Platform.of(PlatformKind.OTHER), "Library card", "reader-20417", "sunshine1", false);
        clock.shift(Duration.ofDays(110));
        add(h, Platform.of(PlatformKind.FACEBOOK), "Family", "maria.demo@example.com", "Shared-Pass-2025!", false);
        add(h, Platform.of(PlatformKind.INSTAGRAM), "Photography", "@sunset.lens.demo", "Shared-Pass-2025!", false);
        clock.shift(Duration.ofDays(30));
        add(h, Platform.of(PlatformKind.GMAIL), "Personal", "maria.demo@example.com", "q7Vd9-KmPz3-XwH6t", true);
        add(h, Platform.of(PlatformKind.BANK), "Savings account", "savings-holder-01", "Rv8#nQ2$mL7!pW4z", true);
        add(h, Platform.of(PlatformKind.TIKTOK), "Cooking clips", "@kusina.demo", "Lx3-Tq9-Hb6-Zr2", false);
        add(h, Platform.of(PlatformKind.X), "Dev updates", "@vault.demo", "Nw5!Kp8@Yc2#Vm6", false);
        add(h, Platform.custom("Home Wi-Fi router"), "Living room", "admin", "Fj4$Ws9!Gd2#Qe7", false);
        h.services.vault().lock(false);

        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> robot.lookup("#unlockKey").tryQuery().isPresent());
        WaitForAsyncUtils.sleep(500, TimeUnit.MILLISECONDS);
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> robot.lookup("#unlockKey").query().requestFocus());
        robot.write("violet anchor brisk tundra 42");
        robot.type(KeyCode.ENTER);
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> table(robot).getItems().size() == 8);
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> {
            EntryRow gmail = table(robot).getItems().stream()
                    .filter(r -> r.entry().accountLabel().equals("Personal")).findFirst().orElseThrow();
            try (Secret pw = UiHarness.s("q7Vd9-KmPz3-XwH6t")) {
                gmail.reveal(pw, Duration.ofSeconds(15));
            }
        });
        WaitForAsyncUtils.sleep(1200, TimeUnit.MILLISECONDS);
        save(stage[0], out.resolve("vault.png"));

        robot.clickOn("#openReport");
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> robot.lookup("#reportSummary").tryQuery().isPresent());
        save(stage[0], out.resolve("security-report.png"));
        robot.clickOn("#reportBack");
        robot.clickOn("#lockNow");
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> robot.lookup("#unlockKey").tryQuery().isPresent());
        robot.clickOn("#unlockHint");
        save(stage[0], out.resolve("unlock.png"));
        h.close();
    }

    private static void add(UiHarness h, Platform p, String label, String user, String pw, boolean fav) {
        try (Secret s = UiHarness.s(pw)) {
            h.services.entries().addEntry(new EntryDetails(p, label, user, null, null), s, fav);
        }
    }

    @SuppressWarnings("unchecked")
    private static TableView<EntryRow> table(FxRobot robot) {
        return (TableView<EntryRow>) robot.lookup("#entries").query();
    }

    private static void save(Window w, Path file) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        CompletableFuture<javafx.scene.image.WritableImage> f = new CompletableFuture<>();
        javafx.application.Platform.runLater(() -> f.complete(w.getScene().snapshot(null)));
        javafx.scene.image.WritableImage img = f.get(10, TimeUnit.SECONDS);
        BufferedImage bi = new BufferedImage((int) img.getWidth(), (int) img.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < bi.getHeight(); y++) {
            for (int x = 0; x < bi.getWidth(); x++) {
                bi.setRGB(x, y, img.getPixelReader().getArgb(x, y));
            }
        }
        ImageIO.write(bi, "png", file.toFile());
    }

    static final class MovableClock implements ClockPort {
        private Duration offset = Duration.ZERO;

        void shift(Duration d) {
            offset = offset.plus(d);
        }

        @Override
        public Instant now() {
            return Instant.now().plus(offset);
        }

        @Override
        public long monotonicNanos() {
            return System.nanoTime();
        }
    }

    /** Demo strength: "sunshine1" weak, everything else strong. */
    static final class DemoStrength implements PasswordStrengthPort {
        @Override
        public StrengthResult estimate(Secret password) {
            String s = new String(password.chars());
            int score = s.equals("sunshine1") ? 0 : s.length() < 12 ? 1 : s.startsWith("Shared") ? 3 : 4;
            return new StrengthResult(score, "", List.of());
        }
    }
}
