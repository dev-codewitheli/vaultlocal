package com.codewitheli.vaultlocal.application.port.out;

/** Cryptographically secure randomness. */
public interface RandomPort {

    /** For long-lived key material: the DEK and salts. */
    byte[] strongBytes(int count);

    /** For nonces and generated passwords. */
    byte[] bytes(int count);

    /** Uniform in {@code [0, bound)}. */
    int nextInt(int bound);
}
