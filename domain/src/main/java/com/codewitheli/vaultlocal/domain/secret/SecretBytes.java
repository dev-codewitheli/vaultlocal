package com.codewitheli.vaultlocal.domain.secret;

import java.util.Arrays;

/** Byte-array twin of {@link Secret}, for key material and encoded secrets. */
public final class SecretBytes implements AutoCloseable {

    private byte[] bytes;

    private SecretBytes(byte[] bytes) {
        this.bytes = bytes;
    }

    /** Takes ownership of {@code bytes}. */
    public static SecretBytes wrap(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null");
        }
        return new SecretBytes(bytes);
    }

    public static SecretBytes copyOf(byte[] source) {
        return new SecretBytes(source.clone());
    }

    /** Direct view. Do not keep a reference beyond the current call. */
    public byte[] bytes() {
        if (bytes == null) {
            throw new IllegalStateException("SecretBytes has been closed");
        }
        return bytes;
    }

    public int length() {
        return bytes().length;
    }

    public SecretBytes copy() {
        return new SecretBytes(bytes().clone());
    }

    public boolean isClosed() {
        return bytes == null;
    }

    @Override
    public void close() {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
            bytes = null;
        }
    }

    @Override
    public String toString() {
        return "SecretBytes[***]";
    }
}
