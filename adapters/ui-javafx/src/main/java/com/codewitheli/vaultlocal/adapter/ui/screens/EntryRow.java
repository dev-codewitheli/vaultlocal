package com.codewitheli.vaultlocal.adapter.ui.screens;

import com.codewitheli.vaultlocal.domain.entry.Entry;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.time.Duration;

/**
 * One table row. The password is masked as a fixed "••••••••" regardless of length; a reveal
 * shows the plaintext for a limited time and then masks it again automatically.
 */
public final class EntryRow {

    public static final String MASK = "•".repeat(8);

    private final ObjectProperty<Entry> entry;
    private final StringProperty password = new SimpleStringProperty(MASK);
    private final IntegerProperty secondsLeft = new SimpleIntegerProperty(0);
    private final ObjectProperty<Integer> strength = new SimpleObjectProperty<>();
    private Timeline timer;

    public EntryRow(Entry entry) {
        this.entry = new SimpleObjectProperty<>(entry);
    }

    public Entry entry() {
        return entry.get();
    }

    public ObjectProperty<Entry> entryProperty() {
        return entry;
    }

    public StringProperty passwordProperty() {
        return password;
    }

    public IntegerProperty secondsLeftProperty() {
        return secondsLeft;
    }

    public ObjectProperty<Integer> strengthProperty() {
        return strength;
    }

    public boolean isRevealed() {
        return secondsLeft.get() > 0;
    }

    /** Shows {@code secret} for {@code duration}. The caller keeps (and closes) the secret. */
    public void reveal(Secret secret, Duration duration) {
        mask();
        // Unavoidable: a Text node can only render a String (DESIGN.md §4.3 (a)).
        password.set(new String(secret.chars()));
        secondsLeft.set((int) duration.toSeconds());
        timer = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), e -> {
            secondsLeft.set(secondsLeft.get() - 1);
            if (secondsLeft.get() <= 0) {
                mask();
            }
        }));
        timer.setCycleCount((int) Math.max(1, duration.toSeconds()));
        timer.play();
    }

    public void mask() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        secondsLeft.set(0);
        password.set(MASK);
    }
}
