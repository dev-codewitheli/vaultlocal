package com.codewitheli.vaultlocal.domain.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Persistent progressive-delay state for one channel. After the n-th consecutive failure the
 * next attempt is refused for {@code min(2^(n-1), 60)} seconds: 1, 2, 4, 8, 16, 32, 60, 60...
 *
 * @param lastFailureAt  {@code null} when there has been no failure since the last success
 * @param lockedUntil    {@code null} when not locked
 */
public record LockoutState(LockoutChannel channel, int consecutiveFailures,
                           Instant lastFailureAt, Instant lockedUntil) {

    public static final Duration MAX_DELAY = Duration.ofSeconds(60);

    public LockoutState {
        Objects.requireNonNull(channel, "channel");
        if (consecutiveFailures < 0) {
            throw new IllegalArgumentException("consecutiveFailures < 0");
        }
    }

    public static LockoutState initial(LockoutChannel channel) {
        return new LockoutState(channel, 0, null, null);
    }

    public static Duration delayAfter(int failures) {
        if (failures <= 0) {
            return Duration.ZERO;
        }
        if (failures - 1 >= 6) {
            return MAX_DELAY;
        }
        long seconds = 1L << (failures - 1);
        return Duration.ofSeconds(Math.min(seconds, MAX_DELAY.toSeconds()));
    }

    /**
     * True when the wall clock is earlier than the last recorded failure, i.e. the system clock
     * was moved backwards. The caller then re-anchors the full delay at "now".
     */
    public boolean clockRolledBack(Instant now) {
        return lastFailureAt != null && now.isBefore(lastFailureAt);
    }

    public LockoutState reanchoredAt(Instant now) {
        return new LockoutState(channel, consecutiveFailures, now, now.plus(delayAfter(consecutiveFailures)));
    }

    public Duration remaining(Instant now) {
        if (lockedUntil == null) {
            return Duration.ZERO;
        }
        if (clockRolledBack(now)) {
            return delayAfter(consecutiveFailures);
        }
        Duration left = Duration.between(now, lockedUntil);
        return left.isNegative() ? Duration.ZERO : left;
    }

    public LockoutState afterFailure(Instant now) {
        int failures = consecutiveFailures == Integer.MAX_VALUE ? consecutiveFailures : consecutiveFailures + 1;
        return new LockoutState(channel, failures, now, now.plus(delayAfter(failures)));
    }

    public LockoutState afterSuccess() {
        return initial(channel);
    }
}
