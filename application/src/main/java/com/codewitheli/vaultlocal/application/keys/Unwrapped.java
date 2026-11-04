package com.codewitheli.vaultlocal.application.keys;

import com.codewitheli.vaultlocal.domain.secret.SecretBytes;

/** The DEK plus the KEK that unwrapped it (kept so the wrap can be resealed without a second KDF run). */
public record Unwrapped(SecretBytes dek, Kek kek) implements AutoCloseable {

    @Override
    public void close() {
        dek.close();
        kek.close();
    }

    @Override
    public String toString() {
        return "Unwrapped[***]";
    }
}
