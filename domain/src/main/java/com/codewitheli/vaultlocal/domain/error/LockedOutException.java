package com.codewitheli.vaultlocal.domain.error;

import java.time.Duration;

/** Raised when an attempt is made while a lockout delay is still running. */
public final class LockedOutException extends VaultException {

    private final transient Duration remaining;

    public LockedOutException(Duration remaining) {
        super(ErrorCode.LOCKED_OUT);
        this.remaining = remaining;
    }

    public Duration remaining() {
        return remaining;
    }
}
