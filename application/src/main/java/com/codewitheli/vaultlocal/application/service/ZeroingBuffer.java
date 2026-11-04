package com.codewitheli.vaultlocal.application.service;

import com.codewitheli.vaultlocal.domain.error.ErrorCode;
import com.codewitheli.vaultlocal.domain.error.VaultException;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import com.codewitheli.vaultlocal.domain.secret.SecretBytes;
import com.codewitheli.vaultlocal.domain.secret.SecretCodec;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A growable byte buffer for the backup payload that zeroes every buffer it discards, plus a
 * matching reader. Strings are length-prefixed UTF-8; secrets never pass through {@link String}.
 */
final class ZeroingBuffer implements AutoCloseable {

    private byte[] buf = new byte[4096];
    private int size;

    void u8(int v) {
        ensure(1);
        buf[size++] = (byte) v;
    }

    void u32(int v) {
        ensure(4);
        buf[size++] = (byte) (v >>> 24);
        buf[size++] = (byte) (v >>> 16);
        buf[size++] = (byte) (v >>> 8);
        buf[size++] = (byte) v;
    }

    void i64(long v) {
        u32((int) (v >>> 32));
        u32((int) v);
    }

    void bytes(byte[] b) {
        u32(b.length);
        ensure(b.length);
        System.arraycopy(b, 0, buf, size, b.length);
        size += b.length;
    }

    void text(String s) {
        bytes(s.getBytes(StandardCharsets.UTF_8));
    }

    void optionalText(String s) {
        u8(s == null ? 0 : 1);
        if (s != null) {
            text(s);
        }
    }

    void secret(Secret s) {
        try (SecretBytes utf8 = SecretCodec.utf8(s)) {
            bytes(utf8.bytes());
        }
    }

    /** A copy of the content; the caller owns (and zeroes) it. */
    byte[] toByteArray() {
        return Arrays.copyOf(buf, size);
    }

    private void ensure(int extra) {
        if (size + extra > buf.length) {
            byte[] bigger = Arrays.copyOf(buf, Math.max(buf.length * 2, size + extra));
            Arrays.fill(buf, (byte) 0);
            buf = bigger;
        }
    }

    @Override
    public void close() {
        Arrays.fill(buf, (byte) 0);
        size = 0;
    }

    /** Sequential reader over a payload; throws {@link ErrorCode#BACKUP_INVALID} on malformed data. */
    static final class Reader {
        private final byte[] data;
        private int pos;

        Reader(byte[] data, int offset) {
            this.data = data;
            this.pos = offset;
        }

        int u8() {
            need(1);
            return data[pos++] & 0xff;
        }

        int u32() {
            need(4);
            int v = ((data[pos] & 0xff) << 24) | ((data[pos + 1] & 0xff) << 16)
                    | ((data[pos + 2] & 0xff) << 8) | (data[pos + 3] & 0xff);
            pos += 4;
            return v;
        }

        long i64() {
            return ((long) u32() << 32) | (u32() & 0xffffffffL);
        }

        byte[] bytes() {
            int len = u32();
            if (len < 0) {
                throw invalid();
            }
            need(len);
            byte[] out = Arrays.copyOfRange(data, pos, pos + len);
            pos += len;
            return out;
        }

        String text() {
            return new String(bytes(), StandardCharsets.UTF_8);
        }

        String optionalText() {
            return u8() == 0 ? null : text();
        }

        Secret secret() {
            byte[] raw = bytes();
            try {
                return SecretCodec.fromUtf8(raw);
            } finally {
                Arrays.fill(raw, (byte) 0);
            }
        }

        int position() {
            return pos;
        }

        boolean atEnd() {
            return pos == data.length;
        }

        private void need(int n) {
            if (n < 0 || pos + n > data.length) {
                throw invalid();
            }
        }

        private static VaultException invalid() {
            return new VaultException(ErrorCode.BACKUP_INVALID);
        }
    }
}
