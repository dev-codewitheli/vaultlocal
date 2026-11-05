package com.codewitheli.vaultlocal.application.port.out;

import java.time.Instant;
import java.util.Objects;

/** One stored wrap of the DEK: KDF parameters, salt, and the sealed DEK. */
public record KeyWrap(WrapType type, KdfParams params, byte[] salt, byte[] wrappedDek, Instant updatedAt) {

    public KeyWrap {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(params, "params");
        salt = salt.clone();
        wrappedDek = wrappedDek.clone();
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    @Override
    public byte[] salt() {
        return salt.clone();
    }

    @Override
    public byte[] wrappedDek() {
        return wrappedDek.clone();
    }

    @Override
    public String toString() {
        return "KeyWrap[" + type + ", " + params + "]";
    }
}
