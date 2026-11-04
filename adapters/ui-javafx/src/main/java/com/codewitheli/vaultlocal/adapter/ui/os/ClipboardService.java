package com.codewitheli.vaultlocal.adapter.ui.os;

import com.codewitheli.vaultlocal.domain.secret.Secret;
import com.codewitheli.vaultlocal.domain.secret.SecretBytes;
import com.codewitheli.vaultlocal.domain.secret.SecretCodec;
import javafx.animation.PauseTransition;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;

/**
 * Copies secrets to the OS clipboard and clears them after a delay, but only if the clipboard
 * still holds our value (compared by an in-memory SHA-256 that is never persisted). Also clears
 * on lock and exit.
 *
 * <p>On Windows the content is additionally tagged with the formats Windows documents for
 * keeping data out of Clipboard History and cloud clipboard sync (decision Q9).
 */
public final class ClipboardService {

    /** Windows: "don't record in clipboard history / don't sync". Value is a DWORD 0. */
    static final String[] WINDOWS_EXCLUSION_FORMATS = {
            "ExcludeClipboardContentFromMonitorProcessing",
            "CanIncludeInClipboardHistory",
            "CanUploadToCloudClipboard",
    };

    private final Duration clearAfter;
    private final boolean windows;
    private byte[] ourDigest;
    private PauseTransition timer;

    public ClipboardService(Duration clearAfter) {
        this.clearAfter = clearAfter;
        this.windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** Must be called on the JavaFX thread. The caller keeps ownership of {@code secret}. */
    public void copy(Secret secret) {
        ClipboardContent content = new ClipboardContent();
        content.putString(new String(secret.chars()));
        if (windows) {
            for (String id : WINDOWS_EXCLUSION_FORMATS) {
                content.put(format(id), ByteBuffer.wrap(new byte[4]));
            }
        }
        Clipboard.getSystemClipboard().setContent(content);
        try (SecretBytes utf8 = SecretCodec.utf8(secret)) {
            forget();
            ourDigest = sha256(utf8.bytes());
        }
        if (timer != null) {
            timer.stop();
        }
        timer = new PauseTransition(javafx.util.Duration.millis(clearAfter.toMillis()));
        timer.setOnFinished(e -> clearIfOurs());
        timer.play();
    }

    /** Clears the clipboard if it still holds the last value we put there. JavaFX thread only. */
    public void clearIfOurs() {
        if (ourDigest == null) {
            return;
        }
        Clipboard clipboard = Clipboard.getSystemClipboard();
        String current = clipboard.hasString() ? clipboard.getString() : null;
        if (current != null && MessageDigest.isEqual(ourDigest, sha256(current.getBytes(StandardCharsets.UTF_8)))) {
            clipboard.clear();
        }
        forget();
        if (timer != null) {
            timer.stop();
        }
    }

    public Duration clearAfter() {
        return clearAfter;
    }

    private void forget() {
        if (ourDigest != null) {
            Arrays.fill(ourDigest, (byte) 0);
            ourDigest = null;
        }
    }

    static DataFormat format(String id) {
        DataFormat existing = DataFormat.lookupMimeType(id);
        return existing != null ? existing : new DataFormat(id);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
