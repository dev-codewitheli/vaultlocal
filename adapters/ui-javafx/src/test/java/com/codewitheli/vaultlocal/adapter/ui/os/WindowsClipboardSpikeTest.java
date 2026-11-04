package com.codewitheli.vaultlocal.adapter.ui.os;

import com.codewitheli.vaultlocal.domain.secret.Secret;
import com.sun.jna.Native;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import javafx.application.Platform;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decision Q9 spike, run with {@code ./gradlew :adapters:ui-javafx:windowsClipboardSpike} on a real
 * Windows desktop (not Monocle). Checks, through the Win32 API, whether the formats that keep
 * data out of Clipboard History / cloud sync are really present after a JavaFX copy.
 * The previous text on the clipboard is restored afterwards.
 */
@Tag("windows-spike")
@EnabledOnOs(OS.WINDOWS)
class WindowsClipboardSpikeTest {

    interface User32Clipboard extends StdCallLibrary {
        User32Clipboard INSTANCE = Native.load("user32", User32Clipboard.class, W32APIOptions.DEFAULT_OPTIONS);

        int RegisterClipboardFormat(String name);

        boolean IsClipboardFormatAvailable(int format);
    }

    @Test
    void exclusionFormatsReachTheWindowsClipboard() throws Exception {
        CompletableFuture<Void> started = new CompletableFuture<>();
        try {
            Platform.startup(() -> started.complete(null));
        } catch (IllegalStateException alreadyRunning) {
            started.complete(null);
        }
        started.get(10, TimeUnit.SECONDS);

        String previous = onFx(() -> Clipboard.getSystemClipboard().getString());
        try {
            ClipboardService service = new ClipboardService(Duration.ofMinutes(5));
            onFx(() -> {
                try (Secret s = Secret.wrap("spike-value".toCharArray())) {
                    service.copy(s);
                }
                return null;
            });
            for (String format : ClipboardService.WINDOWS_EXCLUSION_FORMATS) {
                int id = User32Clipboard.INSTANCE.RegisterClipboardFormat(format);
                boolean present = onFx(() -> User32Clipboard.INSTANCE.IsClipboardFormatAvailable(id));
                System.out.println("SPIKE " + format + " (id " + id + ") present on clipboard: " + present);
                assertThat(present).as(format).isTrue();
            }
            onFx(() -> {
                service.clearIfOurs();
                return null;
            });
        } finally {
            onFx(() -> {
                if (previous != null) {
                    ClipboardContent c = new ClipboardContent();
                    c.putString(previous);
                    Clipboard.getSystemClipboard().setContent(c);
                }
                return null;
            });
        }
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(work.call());
            } catch (Exception e) {
                f.completeExceptionally(e);
            }
        });
        return f.get(10, TimeUnit.SECONDS);
    }
}
