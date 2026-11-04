package com.codewitheli.vaultlocal.domain.secret;

import java.util.Arrays;

/**
 * Owns a {@code char[]} holding secret text (master key, answer, password).
 *
 * <p>Rules: the array is never turned into a {@link String} by this class, {@link #close()}
 * zeroes it, and {@link #toString()} never reveals it. Use try-with-resources. Whoever creates
 * or receives a {@code Secret} is responsible for closing it.
 */
public final class Secret implements AutoCloseable {

    private char[] chars;

    private Secret(char[] chars) {
        this.chars = chars;
    }

    /** Takes ownership of {@code chars}; the caller must not use or zero the array afterwards. */
    public static Secret wrap(char[] chars) {
        if (chars == null) {
            throw new IllegalArgumentException("chars must not be null");
        }
        return new Secret(chars);
    }

    /** Copies a range of {@code source}; the caller keeps ownership of {@code source}. */
    public static Secret copyOf(char[] source, int offset, int length) {
        return new Secret(Arrays.copyOfRange(source, offset, offset + length));
    }

    public static Secret empty() {
        return new Secret(new char[0]);
    }

    /** Direct view of the secret characters. Do not keep a reference beyond the current call. */
    public char[] chars() {
        ensureOpen();
        return chars;
    }

    /** Length in UTF-16 chars. */
    public int length() {
        ensureOpen();
        return chars.length;
    }

    /** Length in Unicode code points, which is what users think of as "characters". */
    public int codePointCount() {
        ensureOpen();
        return Character.codePointCount(chars, 0, chars.length);
    }

    public boolean isEmpty() {
        return length() == 0;
    }

    public Secret copy() {
        ensureOpen();
        return new Secret(chars.clone());
    }

    /** Compares contents without short-circuiting on the first different character. */
    public boolean contentEquals(Secret other) {
        char[] a = chars();
        char[] b = other.chars();
        int diff = a.length ^ b.length;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    public boolean isClosed() {
        return chars == null;
    }

    @Override
    public void close() {
        if (chars != null) {
            Arrays.fill(chars, '\0');
            chars = null;
        }
    }

    private void ensureOpen() {
        if (chars == null) {
            throw new IllegalStateException("Secret has been closed");
        }
    }

    @Override
    public String toString() {
        return "Secret[***]";
    }
}
