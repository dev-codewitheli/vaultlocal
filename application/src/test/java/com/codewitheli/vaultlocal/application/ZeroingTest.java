package com.codewitheli.vaultlocal.application;

import com.codewitheli.vaultlocal.application.port.out.VaultMeta;
import com.codewitheli.vaultlocal.application.service.VaultSession;
import com.codewitheli.vaultlocal.domain.recovery.RecoveryAnswers;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import com.codewitheli.vaultlocal.domain.secret.SecretBytes;
import com.codewitheli.vaultlocal.domain.secret.SecretCodec;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 3: key material and secrets are zeroed in place, not just dereferenced. */
class ZeroingTest {

    @Test
    void lockingTheSessionZeroesTheDekArray() {
        byte[] raw = new byte[32];
        java.util.Arrays.fill(raw, (byte) 0x5A);
        VaultSession session = new VaultSession();
        AtomicBoolean notified = new AtomicBoolean();
        session.addLockListener(() -> notified.set(true));
        session.open(SecretBytes.wrap(raw), new VaultMeta("v", 1, 12, 1, Instant.EPOCH));
        session.lock();
        assertThat(raw).containsOnly((byte) 0);
        assertThat(session.isUnlocked()).isFalse();
        assertThat(notified).isTrue();
    }

    @Test
    void reopeningTheSessionZeroesThePreviousDek() {
        byte[] first = new byte[32];
        java.util.Arrays.fill(first, (byte) 1);
        VaultSession session = new VaultSession();
        VaultMeta meta = new VaultMeta("v", 1, 12, 1, Instant.EPOCH);
        session.open(SecretBytes.wrap(first), meta);
        session.open(SecretBytes.wrap(new byte[32]), meta);
        assertThat(first).containsOnly((byte) 0);
        session.lock();
    }

    @Test
    void recoveryAnswersZeroTheirNormalizedCopies() throws Exception {
        Secret a = Secret.wrap("Answer One".toCharArray());
        Secret b = Secret.wrap("Answer Two".toCharArray());
        Secret c = Secret.wrap("Answer Three".toCharArray());
        RecoveryAnswers answers = RecoveryAnswers.normalize(List.of(a, b, c));
        Field f = RecoveryAnswers.class.getDeclaredField("normalized");
        f.setAccessible(true);
        @SuppressWarnings("unchecked") List<Secret> normalized = (List<Secret>) f.get(answers);
        List<char[]> arrays = normalized.stream().map(Secret::chars).toList();
        answers.close();
        arrays.forEach(arr -> assertThat(arr).containsOnly('\0'));
        a.close();
        b.close();
        c.close();
    }

    @Test
    void utf8EncodingLeavesNoCopyBehind() {
        char[] raw = "pässwörd".toCharArray();
        try (Secret s = Secret.wrap(raw)) {
            SecretBytes utf8 = SecretCodec.utf8(s);
            byte[] view = utf8.bytes();
            utf8.close();
            assertThat(view).containsOnly((byte) 0);
        }
        assertThat(raw).containsOnly('\0');
    }
}
