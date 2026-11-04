package com.codewitheli.vaultlocal.domain.error;

/** Base exception. Its message is always the fixed {@link ErrorCode} text. */
public class VaultException extends RuntimeException {

    private final ErrorCode code;

    public VaultException(ErrorCode code) {
        super(code.message(), null, false, true);
        this.code = code;
    }

    /** Callers must never pass a cause whose message could contain secrets. */
    public VaultException(ErrorCode code, Throwable cause) {
        super(code.message(), cause, false, true);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
