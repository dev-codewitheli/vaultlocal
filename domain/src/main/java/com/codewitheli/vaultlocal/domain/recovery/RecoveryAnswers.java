package com.codewitheli.vaultlocal.domain.recovery;

import com.codewitheli.vaultlocal.domain.error.ErrorCode;
import com.codewitheli.vaultlocal.domain.error.VaultException;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import com.codewitheli.vaultlocal.domain.secret.SecretBytes;
import com.codewitheli.vaultlocal.domain.secret.SecretCodec;

import java.util.ArrayList;
import java.util.List;

/**
 * Three normalized answers, encoded for the recovery KDF as
 * {@code u32_be(len_1) ‖ utf8(a_1) ‖ u32_be(len_2) ‖ utf8(a_2) ‖ u32_be(len_3) ‖ utf8(a_3)}.
 * The length prefixes keep {@code "ab"+"c"} distinct from {@code "a"+"bc"}.
 */
public final class RecoveryAnswers implements AutoCloseable {

    public static final int MIN_ANSWER_CODE_POINTS = 3;

    private final List<Secret> normalized;

    private RecoveryAnswers(List<Secret> normalized) {
        this.normalized = normalized;
    }

    /** Normalizes copies of {@code raw}; the caller still owns and closes the raw answers. */
    public static RecoveryAnswers normalize(List<Secret> raw) {
        if (raw == null || raw.size() != SecurityQuestion.COUNT) {
            throw new VaultException(ErrorCode.QUESTIONS_INVALID);
        }
        List<Secret> out = new ArrayList<>(raw.size());
        for (Secret answer : raw) {
            out.add(AnswerNormalizer.normalize(answer));
        }
        return new RecoveryAnswers(out);
    }

    /** Used at setup and when changing questions; recovery attempts skip this check. */
    public RecoveryAnswers requireMinimumLength() {
        for (Secret s : normalized) {
            if (s.codePointCount() < MIN_ANSWER_CODE_POINTS) {
                throw new VaultException(ErrorCode.ANSWER_TOO_SHORT);
            }
        }
        return this;
    }

    public SecretBytes kdfInput() {
        List<SecretBytes> encoded = new ArrayList<>(normalized.size());
        try {
            int total = 0;
            for (Secret answer : normalized) {
                SecretBytes utf8 = SecretCodec.utf8(answer);
                encoded.add(utf8);
                total += 4 + utf8.length();
            }
            byte[] out = new byte[total];
            int pos = 0;
            for (SecretBytes utf8 : encoded) {
                int len = utf8.length();
                out[pos++] = (byte) (len >>> 24);
                out[pos++] = (byte) (len >>> 16);
                out[pos++] = (byte) (len >>> 8);
                out[pos++] = (byte) len;
                System.arraycopy(utf8.bytes(), 0, out, pos, len);
                pos += len;
            }
            return SecretBytes.wrap(out);
        } finally {
            encoded.forEach(SecretBytes::close);
        }
    }

    @Override
    public void close() {
        normalized.forEach(Secret::close);
    }
}
