package com.codewitheli.vaultlocal.adapter.ui;

import com.codewitheli.vaultlocal.adapter.ui.controls.SecretField;
import com.codewitheli.vaultlocal.adapter.ui.os.AutoLock;
import com.codewitheli.vaultlocal.adapter.ui.os.ClipboardService;
import com.codewitheli.vaultlocal.adapter.ui.screens.EntryRow;
import com.codewitheli.vaultlocal.adapter.ui.screens.VaultViewModel;
import com.codewitheli.vaultlocal.application.port.out.ClockPort;
import com.codewitheli.vaultlocal.domain.entry.Entry;
import com.codewitheli.vaultlocal.domain.entry.EntryDetails;
import com.codewitheli.vaultlocal.domain.entry.EntryId;
import com.codewitheli.vaultlocal.domain.entry.Platform;
import com.codewitheli.vaultlocal.domain.entry.PlatformKind;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import javafx.scene.input.Clipboard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.util.WaitForAsyncUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Search/filter/favorites ordering, stale badge rule, auto-lock, clipboard auto-clear, SecretField. */
@ExtendWith(ApplicationExtension.class)
class OptionalFeaturesUiTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    @Test
    void searchPlatformFilterAndFavoritesFirst(FxRobot robot) {
        VaultViewModel vm = new VaultViewModel();
        Entry gmail = entry(PlatformKind.GMAIL, "Zeta mail", "zed@example.com", false, 0);
        Entry bank = entry(PlatformKind.BANK, "Alpha savings", "acct-1", false, 0);
        Entry fb = entry(PlatformKind.FACEBOOK, "Mid family", "me@fb.example", true, 0);
        robot.interact(() -> vm.setEntries(List.of(gmail, bank, fb), Map.of()));

        vm.sorted().comparatorProperty().set(VaultViewModel.favoritesFirst(null));
        assertThat(vm.sorted()).extracting(r -> r.entry().accountLabel())
                .containsExactly("Mid family", "Alpha savings", "Zeta mail");

        robot.interact(() -> vm.searchProperty().set("ZED@"));
        assertThat(vm.filtered()).extracting(r -> r.entry().accountLabel()).containsExactly("Zeta mail");
        robot.interact(() -> {
            vm.searchProperty().set("");
            vm.platformFilterProperty().set(Platform.of(PlatformKind.BANK));
        });
        assertThat(vm.filtered()).extracting(r -> r.entry().accountLabel()).containsExactly("Alpha savings");
        robot.interact(() -> {
            vm.platformFilterProperty().set(null);
            vm.favoritesOnlyProperty().set(true);
        });
        assertThat(vm.filtered()).extracting(r -> r.entry().accountLabel()).containsExactly("Mid family");
        assertThat(vm.platforms()).extracting(Platform::displayName).containsExactly("Bank", "Facebook", "Gmail");
    }

    @Test
    void staleBadgeRuleUsesPasswordChangeDate() {
        Entry old = entry(PlatformKind.X, "Old", "u", false, 120);
        Entry fresh = entry(PlatformKind.X, "Fresh", "u", false, 10);
        assertThat(VaultViewModel.isStale(old, NOW, 90)).isTrue();
        assertThat(VaultViewModel.isStale(fresh, NOW, 90)).isFalse();
        assertThat(VaultViewModel.isStale(old, NOW, 180)).isFalse();
    }

    @Test
    void revealMasksAgainAutomatically(FxRobot robot) throws Exception {
        EntryRow row = new EntryRow(entry(PlatformKind.GMAIL, "a", "b", false, 0));
        try (Secret pw = Secret.wrap("visible-for-a-moment".toCharArray())) {
            robot.interact(() -> row.reveal(pw, Duration.ofSeconds(1)));
        }
        assertThat(row.passwordProperty().get()).isEqualTo("visible-for-a-moment");
        assertThat(row.passwordProperty().get()).isNotEqualTo(EntryRow.MASK);
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> EntryRow.MASK.equals(row.passwordProperty().get()));
        assertThat(EntryRow.MASK).hasSize(8);
    }

    @Test
    void autoLockFiresAfterIdleAndAfterSleepGap(FxRobot robot) {
        FakeClock clock = new FakeClock();
        AtomicInteger locks = new AtomicInteger();
        AutoLock[] holder = new AutoLock[1];
        robot.interact(() -> holder[0] = new AutoLock(clock, () -> 5, locks::incrementAndGet,
                Duration.ofHours(1), Duration.ofSeconds(30)));
        AutoLock auto = holder[0];

        robot.interact(auto::arm);
        runFor(robot, auto, clock, Duration.ofMinutes(4));
        assertThat(locks).hasValue(0);
        robot.interact(auto::touch);
        runFor(robot, auto, clock, Duration.ofMinutes(4));
        assertThat(locks).as("activity resets the idle timer").hasValue(0);
        runFor(robot, auto, clock, Duration.ofSeconds(62));
        assertThat(locks).as("5 minutes without input").hasValue(1);

        robot.interact(auto::arm);
        runFor(robot, auto, clock, Duration.ofSeconds(10));
        clock.advance(Duration.ofMinutes(2));
        robot.interact(() -> tick(auto));
        assertThat(locks).as("a 2-minute gap between 2-second ticks means the machine slept").hasValue(2);

        robot.interact(auto::disarm);
        runFor(robot, auto, clock, Duration.ofMinutes(10));
        assertThat(locks).as("no lock while already locked").hasValue(2);
    }

    @Test
    void clipboardClearsItselfOnlyIfItStillHoldsOurValue(FxRobot robot) throws Exception {
        ClipboardService clipboard = new ClipboardService(Duration.ofSeconds(1));
        try (Secret pw = Secret.wrap("clip-me-123".toCharArray())) {
            robot.interact(() -> clipboard.copy(pw));
        }
        robot.interact(() -> assertThat(Clipboard.getSystemClipboard().getString()).isEqualTo("clip-me-123"));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> {
            String[] v = new String[1];
            robot.interact(() -> v[0] = Clipboard.getSystemClipboard().getString());
            return v[0] == null || v[0].isEmpty();
        });

        try (Secret pw = Secret.wrap("clip-me-again".toCharArray())) {
            robot.interact(() -> clipboard.copy(pw));
        }
        robot.interact(() -> {
            javafx.scene.input.ClipboardContent other = new javafx.scene.input.ClipboardContent();
            other.putString("something the user copied later");
            Clipboard.getSystemClipboard().setContent(other);
        });
        WaitForAsyncUtils.sleep(1500, TimeUnit.MILLISECONDS);
        robot.interact(() -> assertThat(Clipboard.getSystemClipboard().getString())
                .isEqualTo("something the user copied later"));
    }

    @Test
    void secretFieldEditsWithoutExposingText(FxRobot robot) {
        SecretField[] f = new SecretField[1];
        robot.interact(() -> {
            f[0] = new SecretField();
            javafx.stage.Stage stage = new javafx.stage.Stage();
            stage.setScene(new javafx.scene.Scene(new javafx.scene.layout.StackPane(f[0]), 300, 80));
            stage.show();
        });
        robot.clickOn(f[0]).write("ab😀cd");
        robot.type(javafx.scene.input.KeyCode.BACK_SPACE);
        robot.type(javafx.scene.input.KeyCode.LEFT);
        robot.type(javafx.scene.input.KeyCode.BACK_SPACE); // removes the emoji as one character
        assertThat(f[0].codePointCount()).isEqualTo(3);
        assertThat(f[0].toString()).isEqualTo("SecretField[***]");
        char[] internal = internalBuffer(f[0]);
        try (Secret s = f[0].takeSecret()) {
            assertThat(new String(s.chars())).isEqualTo("abc");
        }
        assertThat(f[0].isEmpty()).isTrue();
        assertThat(internal).as("SecretField zeroes its own buffer after handing the secret over").containsOnly('\0');
    }

    private static char[] internalBuffer(SecretField field) {
        try {
            var b = SecretField.class.getDeclaredField("buffer");
            b.setAccessible(true);
            return (char[]) b.get(field);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Simulates the real 2-second timer ticking for {@code total}. */
    private static void runFor(FxRobot robot, AutoLock auto, FakeClock clock, Duration total) {
        robot.interact(() -> {
            for (long s = 0; s < total.toSeconds(); s += 2) {
                clock.advance(Duration.ofSeconds(2));
                tick(auto);
            }
        });
    }

    private static void tick(AutoLock auto) {
        try {
            var m = AutoLock.class.getDeclaredMethod("onTick");
            m.setAccessible(true);
            m.invoke(auto);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Entry entry(PlatformKind kind, String label, String user, boolean fav, int pwAgeDays) {
        Instant changed = NOW.minus(Duration.ofDays(pwAgeDays));
        return new Entry(EntryId.newId(), new EntryDetails(Platform.of(kind), label, user, null, null), fav,
                changed, changed, changed);
    }

    static final class FakeClock implements ClockPort {
        private Instant now = NOW;
        private long nanos = 1_000_000_000L;

        void advance(Duration d) {
            now = now.plus(d);
            nanos += d.toNanos();
        }

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public long monotonicNanos() {
            return nanos;
        }
    }
}
