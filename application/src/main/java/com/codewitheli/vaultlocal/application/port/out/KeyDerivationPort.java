package com.codewitheli.vaultlocal.application.port.out;

import com.codewitheli.vaultlocal.domain.secret.SecretBytes;

/** Password-based key derivation (Argon2id). */
public interface KeyDerivationPort {

    int KEY_BYTES = 32;

    /** Derives a 256-bit key. The caller keeps ownership of {@code input} and closes the result. */
    SecretBytes derive(SecretBytes input, byte[] salt, KdfParams params);
}
