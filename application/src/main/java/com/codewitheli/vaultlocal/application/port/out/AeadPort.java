package com.codewitheli.vaultlocal.application.port.out;

import com.codewitheli.vaultlocal.domain.secret.SecretBytes;

import java.util.Optional;

/**
 * Authenticated encryption (AES-256-GCM). Blobs are self-describing:
 * {@code version(1) ‖ nonce(12) ‖ ciphertext ‖ tag(16)}; the adapter picks a fresh random
 * nonce for every {@link #seal} call.
 */
public interface AeadPort {

    byte[] seal(SecretBytes key, byte[] plaintext, byte[] aad);

    /**
     * @return the plaintext, or empty when the key, AAD or blob doesn't authenticate. The caller
     *         owns the returned array and must zero it when it holds secret data.
     */
    Optional<byte[]> open(SecretBytes key, byte[] blob, byte[] aad);
}
