package com.codewitheli.vaultlocal.adapter.crypto;

import com.codewitheli.vaultlocal.application.port.out.RandomPort;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * {@link SecureRandom#getInstanceStrong()} for long-lived keys and salts; the default
 * {@link SecureRandom} (non-blocking) for nonces and generated passwords.
 */
public final class SecureRandomSource implements RandomPort {

    private final SecureRandom strong;
    private final SecureRandom regular;

    public SecureRandomSource() {
        try {
            this.strong = SecureRandom.getInstanceStrong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("No strong SecureRandom available", e);
        }
        this.regular = new SecureRandom();
    }

    @Override
    public byte[] strongBytes(int count) {
        byte[] out = new byte[count];
        strong.nextBytes(out);
        return out;
    }

    @Override
    public byte[] bytes(int count) {
        byte[] out = new byte[count];
        regular.nextBytes(out);
        return out;
    }

    @Override
    public int nextInt(int bound) {
        return regular.nextInt(bound);
    }
}
