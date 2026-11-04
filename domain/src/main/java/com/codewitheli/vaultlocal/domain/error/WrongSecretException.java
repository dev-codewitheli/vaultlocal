package com.codewitheli.vaultlocal.domain.error;

import java.time.Duration;

/** Wrong master key or wrong answers. Carries the lockout this failure has just started. */
public final class WrongSecretException extends VaultException {

    private final transient Duration lockout;

    public WrongSecretException(ErrorCode code, Duration lockout) {
        super(code);
        this.lockout = lockout;
    }

    public Duration lockout() {
        return lockout;
    }
}
