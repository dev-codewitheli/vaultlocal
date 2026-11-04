package com.codewitheli.vaultlocal.adapter.crypto;

import com.codewitheli.vaultlocal.application.port.out.AeadPort;
import com.codewitheli.vaultlocal.application.port.out.RandomPort;
import com.codewitheli.vaultlocal.domain.secret.SecretBytes;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Optional;

/**
 * AES-256-GCM from the JDK (SunJCE). Blob: {@code 0x01 ‖ nonce(12) ‖ ciphertext ‖ tag(16)}.
 * Note: {@link SecretKeySpec} and the cipher's key schedule keep copies of the key that cannot be
 * wiped; they become unreachable right after each call (DESIGN.md §4.3).
 */
public final class AesGcmAead implements AeadPort {

    static final byte FORMAT_VERSION = 0x01;
    static final int NONCE_BYTES = 12;
    static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final RandomPort random;

    public AesGcmAead(RandomPort random) {
        this.random = random;
    }

    @Override
    public byte[] seal(SecretBytes key, byte[] plaintext, byte[] aad) {
        return sealWithNonce(key.bytes(), random.bytes(NONCE_BYTES), plaintext, aad);
    }

    static byte[] sealWithNonce(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) {
        requireAes256(key);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            byte[] out = new byte[1 + NONCE_BYTES + cipher.getOutputSize(plaintext.length)];
            out[0] = FORMAT_VERSION;
            System.arraycopy(nonce, 0, out, 1, NONCE_BYTES);
            cipher.doFinal(plaintext, 0, plaintext.length, out, 1 + NONCE_BYTES);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM unavailable", e);
        }
    }

    @Override
    public Optional<byte[]> open(SecretBytes key, byte[] blob, byte[] aad) {
        requireAes256(key.bytes());
        if (blob == null || blob.length < 1 + NONCE_BYTES + TAG_BITS / 8 || blob[0] != FORMAT_VERSION) {
            return Optional.empty();
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.bytes(), "AES"),
                    new GCMParameterSpec(TAG_BITS, blob, 1, NONCE_BYTES));
            cipher.updateAAD(aad);
            byte[] out = new byte[cipher.getOutputSize(blob.length - 1 - NONCE_BYTES)];
            int n = cipher.doFinal(blob, 1 + NONCE_BYTES, blob.length - 1 - NONCE_BYTES, out, 0);
            if (n == out.length) {
                return Optional.of(out);
            }
            byte[] exact = Arrays.copyOf(out, n);
            Arrays.fill(out, (byte) 0);
            return Optional.of(exact);
        } catch (AEADBadTagException e) {
            return Optional.empty();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM unavailable", e);
        }
    }

    private static void requireAes256(byte[] key) {
        if (key.length != 32) {
            throw new IllegalArgumentException("AES-256 key required");
        }
    }
}
