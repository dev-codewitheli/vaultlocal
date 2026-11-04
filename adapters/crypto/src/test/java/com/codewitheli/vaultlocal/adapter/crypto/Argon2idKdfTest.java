package com.codewitheli.vaultlocal.adapter.crypto;

import com.codewitheli.vaultlocal.application.keys.KdfProfile;
import com.codewitheli.vaultlocal.application.port.out.KdfParams;
import com.codewitheli.vaultlocal.domain.secret.SecretBytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class Argon2idKdfTest {

    private final Argon2idKdf kdf = new Argon2idKdf();

    /** RFC 9106 §5.3 Argon2id test vector. */
    @Test
    void matchesRfc9106TestVector() {
        byte[] password = filled(32, 0x01);
        byte[] salt = filled(16, 0x02);
        byte[] secret = filled(8, 0x03);
        byte[] ad = filled(12, 0x04);
        byte[] tag = Argon2idKdf.generate(password, salt, KdfParams.argon2id(32, 3, 4), secret, ad, 32);
        assertThat(HexFormat.of().formatHex(tag))
                .isEqualTo("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659");
    }

    @Test
    void sameInputSameKeyDifferentSaltDifferentKey() {
        KdfParams params = KdfProfile.FOR_TESTS.master();
        try (SecretBytes input = SecretBytes.wrap("correct horse".getBytes());
             SecretBytes k1 = kdf.derive(input, filled(16, 7), params);
             SecretBytes k2 = kdf.derive(input, filled(16, 7), params);
             SecretBytes k3 = kdf.derive(input, filled(16, 8), params)) {
            assertThat(k1.length()).isEqualTo(32);
            assertThat(k1.bytes()).isEqualTo(k2.bytes());
            assertThat(k1.bytes()).isNotEqualTo(k3.bytes());
        }
    }

    @Test
    void productionParametersArePinned() {
        assertThat(KdfProfile.PRODUCTION.master()).isEqualTo(KdfParams.argon2id(131_072, 3, 4));
        assertThat(KdfProfile.PRODUCTION.recovery()).isEqualTo(KdfParams.argon2id(262_144, 6, 4));
    }

    /** Run with {@code ./gradlew :adapters:crypto:test -Pbenchmark}. */
    @Test
    @EnabledIfSystemProperty(named = "vaultlocal.benchmark", matches = "true")
    void benchmarkProductionParameters() {
        for (int round = 0; round < 2; round++) {
            time("master  ", KdfProfile.PRODUCTION.master());
            time("recovery", KdfProfile.PRODUCTION.recovery());
        }
    }

    private void time(String label, KdfParams params) {
        try (SecretBytes input = SecretBytes.wrap(new byte[24])) {
            long start = System.nanoTime();
            kdf.derive(input, new byte[16], params).close();
            System.out.printf("BENCHMARK %s %s: %d ms%n", label, params, (System.nanoTime() - start) / 1_000_000);
        }
    }

    private static byte[] filled(int n, int value) {
        byte[] b = new byte[n];
        Arrays.fill(b, (byte) value);
        return b;
    }
}
