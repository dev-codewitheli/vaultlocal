package com.codewitheli.vaultlocal.domain.entry;

import com.codewitheli.vaultlocal.domain.error.ErrorCode;
import com.codewitheli.vaultlocal.domain.error.VaultException;
import com.codewitheli.vaultlocal.domain.secret.Secret;

/** Validation for entry passwords. */
public final class PasswordRules {

    public static final int MAX_LENGTH = 1024;

    private PasswordRules() {
    }

    public static void validate(Secret password) {
        if (password == null || password.isEmpty() || password.length() > MAX_LENGTH) {
            throw new VaultException(ErrorCode.ENTRY_INVALID);
        }
    }
}
