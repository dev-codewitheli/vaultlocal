package com.codewitheli.vaultlocal.domain.policy;

import java.time.Duration;
import java.time.Instant;

/** A password is stale when it hasn't been changed for N days (decision Q6). */
public final class StalenessPolicy {

    public static final int DEFAULT_DAYS = 90;
    public static final int MIN_DAYS = 7;
    public static final int MAX_DAYS = 3650;

    private StalenessPolicy() {
    }

    public static boolean isStale(Instant passwordChangedAt, Instant now, int days) {
        return Duration.between(passwordChangedAt, now).toDays() >= days;
    }
}
