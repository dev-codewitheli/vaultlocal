package com.codewitheli.vaultlocal.domain.policy;

import com.codewitheli.vaultlocal.domain.error.ErrorCode;
import com.codewitheli.vaultlocal.domain.error.VaultException;
import com.codewitheli.vaultlocal.domain.secret.Secret;

/** Minimum requirements for a master key (DESIGN.md §6.1, decision Q3). */
public final class MasterKeyPolicy {

    public static final int MIN_CODE_POINTS = 12;
    public static final int MAX_CODE_POINTS = 256;
    /** zxcvbn-style score 0..4; anything below this is rejected even when long enough. */
    public static final int MIN_STRENGTH_SCORE = 2;

    private MasterKeyPolicy() {
    }

    public static void validate(Secret masterKey, int strengthScore) {
        int length = masterKey.codePointCount();
        if (length < MIN_CODE_POINTS || length > MAX_CODE_POINTS) {
            throw new VaultException(ErrorCode.MASTER_KEY_TOO_SHORT);
        }
        if (strengthScore < MIN_STRENGTH_SCORE) {
            throw new VaultException(ErrorCode.MASTER_KEY_TOO_WEAK);
        }
    }
}
