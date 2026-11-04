package com.codewitheli.vaultlocal.adapter.ui.os;

import com.codewitheli.vaultlocal.application.port.out.ClockPort;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.InputEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.function.IntSupplier;

/**
 * Locks the vault after N idle minutes, after a sleep/suspend (detected as a gap between timer
 * ticks on either the wall clock or the monotonic clock), and on OS session-lock signals.
 */
public final class AutoLock {

    private final ClockPort clock;
    private final IntSupplier idleMinutes;
    private final Runnable lockAction;
    private final Duration tick;
    private final Duration sleepGap;
    private final Timeline timeline;
    private long lastActivityNanos;
    private long lastTickNanos;
    private Instant lastTickWall;
    private boolean armed;

    public AutoLock(ClockPort clock, IntSupplier idleMinutes, Runnable lockAction, Duration tick, Duration sleepGap) {
        this.clock = clock;
        this.idleMinutes = idleMinutes;
        this.lockAction = lockAction;
        this.tick = tick;
        this.sleepGap = sleepGap;
        this.timeline = new Timeline(new KeyFrame(javafx.util.Duration.millis(tick.toMillis()), e -> onTick()));
        this.timeline.setCycleCount(Timeline.INDEFINITE);
    }

    public void install(Scene scene, SessionLockSource osSignals) {
        scene.addEventFilter(InputEvent.ANY, e -> touch());
        osSignals.start(() -> Platform.runLater(this::fire));
        timeline.play();
    }

    /** Called when the vault is unlocked: starts counting idle time from now. */
    public void arm() {
        armed = true;
        touch();
        lastTickNanos = clock.monotonicNanos();
        lastTickWall = clock.now();
    }

    public void disarm() {
        armed = false;
    }

    public void touch() {
        lastActivityNanos = clock.monotonicNanos();
    }

    void onTick() {
        long nowNanos = clock.monotonicNanos();
        Instant nowWall = clock.now();
        boolean slept = lastTickWall != null
                && (Duration.between(lastTickWall, nowWall).compareTo(sleepGap) > 0
                || Duration.ofNanos(nowNanos - lastTickNanos).compareTo(sleepGap) > 0);
        lastTickNanos = nowNanos;
        lastTickWall = nowWall;
        if (!armed) {
            return;
        }
        boolean idle = Duration.ofNanos(nowNanos - lastActivityNanos).compareTo(Duration.ofMinutes(idleMinutes.getAsInt())) >= 0;
        if (slept || idle) {
            fire();
        }
    }

    private void fire() {
        if (armed) {
            armed = false;
            lockAction.run();
        }
    }

    public Duration tickInterval() {
        return tick;
    }
}
