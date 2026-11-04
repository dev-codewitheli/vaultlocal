package com.codewitheli.vaultlocal.adapter.ui.controls;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Label;

import java.time.Duration;

/**
 * Shows "Too many attempts. Try again in N s." and keeps {@link #lockedProperty()} true until the
 * persisted delay has run out. Bind input fields' {@code disable} to it.
 */
public final class LockoutCountdown extends Label {

    private final BooleanProperty locked = new SimpleBooleanProperty(false);
    private Timeline timeline;
    private long endNanos;

    public LockoutCountdown() {
        getStyleClass().add("lockout");
        setWrapText(true);
        managedProperty().bind(visibleProperty());
        setVisible(false);
    }

    public void start(Duration remaining) {
        stop();
        if (remaining == null || remaining.isZero() || remaining.isNegative()) {
            return;
        }
        endNanos = System.nanoTime() + remaining.toNanos();
        locked.set(true);
        setVisible(true);
        update();
        timeline = new Timeline(new KeyFrame(javafx.util.Duration.millis(200), e -> update()));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
    }

    private void update() {
        long left = endNanos - System.nanoTime();
        if (left <= 0) {
            stop();
            return;
        }
        long seconds = (left + 999_999_999L) / 1_000_000_000L;
        setText("Too many failed attempts. Try again in " + seconds + (seconds == 1 ? " second." : " seconds."));
    }

    public void stop() {
        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }
        locked.set(false);
        setVisible(false);
    }

    public BooleanProperty lockedProperty() {
        return locked;
    }
}
