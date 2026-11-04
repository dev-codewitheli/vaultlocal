package com.codewitheli.vaultlocal.application.keys;

import com.codewitheli.vaultlocal.application.port.out.KdfParams;
import com.codewitheli.vaultlocal.domain.secret.SecretBytes;

/** A key-encryption key together with the salt and params that produced it. */
public record Kek(KdfParams params, byte[] salt, SecretBytes key) implements AutoCloseable {

    @Override
    public void close() {
        key.close();
    }

    @Override
    public String toString() {
        return "Kek[***]";
    }
}
