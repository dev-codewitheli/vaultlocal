package com.codewitheli.vaultlocal.domain.recovery;

import com.codewitheli.vaultlocal.domain.secret.Secret;

import java.util.Arrays;

/**
 * Normalizes a security answer without ever building a {@link String}:
 * <ol>
 *   <li>leading and trailing whitespace is removed,</li>
 *   <li>each run of whitespace becomes a single space,</li>
 *   <li>letters are lower-cased per code point, independent of the OS locale.</li>
 * </ol>
 * Everything else (punctuation, accents, digits) must match exactly. Unicode NFC normalization
 * is not applied, because {@code java.text.Normalizer} only produces {@code String}s.
 */
public final class AnswerNormalizer {

    /** Shown to the user, word for word, next to the answer fields. */
    public static final String RULES_TEXT = "Spaces at the start and end are ignored. "
            + "Several spaces in a row count as one. Upper/lower case doesn't matter. "
            + "Everything else, including punctuation and accents, must match exactly.";

    private AnswerNormalizer() {
    }

    public static Secret normalize(Secret raw) {
        char[] src = raw.chars();
        // A lower-case mapping never needs more than 2 UTF-16 units per code point.
        char[] out = new char[src.length * 2];
        int o = 0;
        boolean started = false;
        boolean pendingSpace = false;
        try {
            for (int i = 0; i < src.length; ) {
                int cp = Character.codePointAt(src, i);
                i += Character.charCount(cp);
                if (isWhitespace(cp)) {
                    pendingSpace = started;
                    continue;
                }
                if (pendingSpace) {
                    out[o++] = ' ';
                    pendingSpace = false;
                }
                started = true;
                o += Character.toChars(Character.toLowerCase(cp), out, o);
            }
            return Secret.copyOf(out, 0, o);
        } finally {
            Arrays.fill(out, '\0');
        }
    }

    static boolean isWhitespace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }
}
