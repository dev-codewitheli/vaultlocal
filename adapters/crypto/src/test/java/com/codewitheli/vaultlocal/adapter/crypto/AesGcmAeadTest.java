package com.codewitheli.vaultlocal.adapter.crypto;

import com.codewitheli.vaultlocal.domain.secret.SecretBytes;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AesGcmAeadTest {

    private final SecureRandomSource random = new SecureRandomSource();
    private final AesGcmAead aead = new AesGcmAead(random);
    private final byte[] aad = "VL1|test".getBytes(StandardCharsets.UTF_8);

    /** McGrew &amp; Viega GCM spec, test cases 13 and 14 (AES-256, zero key, zero IV). */
    @Test
    void matchesGcmSpecVectors() {
        HexFormat hex = HexFormat.of();
        byte[] key = new byte[32];
        byte[] iv = new byte[12];
        byte[] tc13 = AesGcmAead.sealWithNonce(key, iv, new byte[0], new byte[0]);
        assertThat(hex.formatHex(tc13, 13, tc13.length)).isEqualTo("530f8afbc74536b9a963b4f1c4cb738b");
        byte[] tc14 = AesGcmAead.sealWithNonce(key, iv, new byte[16], new byte[0]);
        assertThat(hex.formatHex(tc14, 13, tc14.length))
                .isEqualTo("cea7403d4d606b6e074ec5d3baf39d18" + "d0d1c8a799996bf0265b98b5d48ab919");
    }

    @Test
    void roundTripAndBlobLayout() {
        try (SecretBytes key = SecretBytes.wrap(random.strongBytes(32))) {
            byte[] blob = aead.seal(key, "s3cret".getBytes(StandardCharsets.UTF_8), aad);
            assertThat(blob[0]).isEqualTo((byte) 1);
            assertThat(blob).hasSize(1 + 12 + 6 + 16);
            assertThat(aead.open(key, blob, aad)).hasValueSatisfying(
                    p -> assertThat(new String(p, StandardCharsets.UTF_8)).isEqualTo("s3cret"));
        }
    }

    @Test
    void tamperingWrongAadOrWrongKeyFailClosed() {
        try (SecretBytes key = SecretBytes.wrap(random.strongBytes(32));
             SecretBytes other = SecretBytes.wrap(random.strongBytes(32))) {
            byte[] blob = aead.seal(key, new byte[]{1, 2, 3}, aad);
            for (int i = 0; i < blob.length; i++) {
                byte[] tampered = blob.clone();
                tampered[i] ^= 0x01;
                assertThat(aead.open(key, tampered, aad)).as("flipped byte %d", i).isEmpty();
            }
            assertThat(aead.open(key, blob, "VL1|other".getBytes(StandardCharsets.UTF_8))).isEmpty();
            assertThat(aead.open(other, blob, aad)).isEmpty();
            assertThat(aead.open(key, new byte[5], aad)).isEmpty();
        }
    }

    @Test
    void everySealUsesAFreshNonce() {
        try (SecretBytes key = SecretBytes.wrap(random.strongBytes(32))) {
            Set<String> nonces = new HashSet<>();
            for (int i = 0; i < 100_000; i++) {
                byte[] blob = aead.seal(key, new byte[0], aad);
                nonces.add(HexFormat.of().formatHex(blob, 1, 13));
            }
            assertThat(nonces).hasSize(100_000);
        }
    }
}
