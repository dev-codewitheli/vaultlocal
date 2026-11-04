package com.codewitheli.vaultlocal.domain.policy;

/** Idle auto-lock bounds, in minutes. */
public final class AutoLockPolicy {

    public static final int DEFAULT_MINUTES = 5;
    public static final int MIN_MINUTES = 1;
    public static final int MAX_MINUTES = 60;

    private AutoLockPolicy() {
    }

    public static int clamp(int minutes) {
        return Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, minutes));
    }
}
