package com.codewitheli.vaultlocal.adapter.crypto;

import com.codewitheli.vaultlocal.application.keys.KdfProfile;
import com.codewitheli.vaultlocal.application.keys.KeyHierarchy;
import com.codewitheli.vaultlocal.application.keys.Unwrapped;
import com.codewitheli.vaultlocal.application.port.out.KeyWrap;
import com.codewitheli.vaultlocal.application.port.out.WrapType;
import com.codewitheli.vaultlocal.domain.recovery.RecoveryAnswers;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import com.codewitheli.vaultlocal.domain.secret.SecretBytes;
import com.codewitheli.vaultlocal.domain.secret.SecretCodec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Envelope encryption end to end with the real Argon2id and AES-GCM adapters (test KDF costs). */
class KeyHierarchyTest {

    private static final String VAULT = "11111111-2222-3333-4444-555555555555";
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private final SecureRandomSource random = new SecureRandomSource();
    private final KeyHierarchy keys = new KeyHierarchy(new Argon2idKdf(), new AesGcmAead(random), random);

    @Test
    void bothWrapsUnwrapTheSameDek() {
        try (SecretBytes dek = keys.newDek();
             SecretBytes master = utf8("correct horse battery staple");
             SecretBytes answers = answers("Fluffy", "Metallica", "Toyota Corolla")) {
            KeyWrap mWrap = keys.wrapNew(WrapType.MASTER, master, KdfProfile.FOR_TESTS.master(), dek, VAULT, 1, NOW);
            KeyWrap rWrap = keys.wrapNew(WrapType.RECOVERY, answers, KdfProfile.FOR_TESTS.recovery(), dek, VAULT, 1, NOW);

            assertThat(mWrap.salt()).hasSize(16).isNotEqualTo(rWrap.salt());
            assertThat(indexOf(mWrap.wrappedDek(), dek.bytes())).isEqualTo(-1);

            try (Unwrapped viaMaster = keys.unwrap(mWrap, master, VAULT, 1).orElseThrow();
                 SecretBytes sameAnswersDifferentCase = answers("  FLUFFY", "metallica", "toyota   corolla ");
                 Unwrapped viaAnswers = keys.unwrap(rWrap, sameAnswersDifferentCase, VAULT, 1).orElseThrow()) {
                assertThat(viaMaster.dek().bytes()).isEqualTo(dek.bytes());
                assertThat(viaAnswers.dek().bytes()).isEqualTo(dek.bytes());
            }
        }
    }

    @Test
    void wrongSecretsFailWithoutAnyStoredVerifier() {
        try (SecretBytes dek = keys.newDek();
             SecretBytes master = utf8("correct horse battery staple");
             SecretBytes wrong = utf8("correct horse battery stapl3");
             SecretBytes answers = answers("Fluffy", "Metallica", "Toyota Corolla");
             SecretBytes wrongAnswers = answers("Fluffy", "Metallica", "Honda Civic")) {
            KeyWrap mWrap = keys.wrapNew(WrapType.MASTER, master, KdfProfile.FOR_TESTS.master(), dek, VAULT, 1, NOW);
            KeyWrap rWrap = keys.wrapNew(WrapType.RECOVERY, answers, KdfProfile.FOR_TESTS.recovery(), dek, VAULT, 1, NOW);
            assertThat(keys.unwrap(mWrap, wrong, VAULT, 1)).isEmpty();
            assertThat(keys.unwrap(rWrap, wrongAnswers, VAULT, 1)).isEmpty();
            assertThat(keys.verifies(mWrap, master, VAULT, 1)).isTrue();
        }
    }

    @Test
    void wrapsAreBoundToTheirTypeVaultAndGeneration() {
        try (SecretBytes dek = keys.newDek(); SecretBytes master = utf8("correct horse battery staple")) {
            KeyWrap mWrap = keys.wrapNew(WrapType.MASTER, master, KdfProfile.FOR_TESTS.master(), dek, VAULT, 1, NOW);
            KeyWrap relabelled = new KeyWrap(WrapType.RECOVERY, mWrap.params(), mWrap.salt(), mWrap.wrappedDek(), NOW);
            assertThat(keys.unwrap(relabelled, master, VAULT, 1)).isEmpty();
            assertThat(keys.unwrap(mWrap, master, "99999999-2222-3333-4444-555555555555", 1)).isEmpty();
            assertThat(keys.unwrap(mWrap, master, VAULT, 2)).isEmpty();
        }
    }

    @Test
    void rewrapWithNewMasterKeyInvalidatesTheOldOne() {
        try (SecretBytes dek = keys.newDek();
             SecretBytes oldKey = utf8("old master key 123");
             SecretBytes newKey = utf8("new master key 456")) {
            KeyWrap oldWrap = keys.wrapNew(WrapType.MASTER, oldKey, KdfProfile.FOR_TESTS.master(), dek, VAULT, 1, NOW);
            KeyWrap newWrap = keys.wrapNew(WrapType.MASTER, newKey, KdfProfile.FOR_TESTS.master(), dek, VAULT, 1, NOW);
            assertThat(newWrap.salt()).isNotEqualTo(oldWrap.salt());
            assertThat(keys.unwrap(newWrap, oldKey, VAULT, 1)).isEmpty();
            Optional<Unwrapped> ok = keys.unwrap(newWrap, newKey, VAULT, 1);
            assertThat(ok).isPresent();
            ok.get().close();
        }
    }

    @Test
    void resealingWithTheHeldKekNeedsNoSecondKdfRun() {
        try (SecretBytes dek = keys.newDek();
             SecretBytes newDek = keys.newDek();
             SecretBytes answers = answers("aaa", "bbb", "ccc")) {
            KeyWrap rWrap = keys.wrapNew(WrapType.RECOVERY, answers, KdfProfile.FOR_TESTS.recovery(), dek, VAULT, 1, NOW);
            try (Unwrapped u = keys.unwrap(rWrap, answers, VAULT, 1).orElseThrow()) {
                KeyWrap resealed = keys.seal(WrapType.RECOVERY, u.kek(), newDek, VAULT, 2, NOW);
                assertThat(resealed.salt()).isEqualTo(rWrap.salt());
                try (Unwrapped again = keys.unwrap(resealed, answers, VAULT, 2).orElseThrow()) {
                    assertThat(again.dek().bytes()).isEqualTo(newDek.bytes());
                }
            }
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static SecretBytes utf8(String s) {
        try (Secret secret = Secret.wrap(s.toCharArray())) {
            return SecretCodec.utf8(secret);
        }
    }

    private static SecretBytes answers(String a, String b, String c) {
        try (Secret sa = Secret.wrap(a.toCharArray());
             Secret sb = Secret.wrap(b.toCharArray());
             Secret sc = Secret.wrap(c.toCharArray());
             RecoveryAnswers ra = RecoveryAnswers.normalize(List.of(sa, sb, sc))) {
            return ra.kdfInput();
        }
    }
}
