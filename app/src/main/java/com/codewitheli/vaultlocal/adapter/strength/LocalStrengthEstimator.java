package com.codewitheli.vaultlocal.adapter.strength;

import com.codewitheli.vaultlocal.application.port.out.PasswordStrengthPort;
import com.codewitheli.vaultlocal.domain.secret.Secret;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * In-house strength estimator (decision Q8: zxcvbn4j builds {@code String}s from the password, so it
 * was rejected). Works only on {@code char[]} copies that are zeroed before returning.
 *
 * <p>Model: entropy = Σ(per-character weight) × log2(character pool), where repeats, sequences
 * (abc, 321) and keyboard runs (qwe, asd) weigh less, and common words/passwords (also in l33t
 * form) count as one guess from a ~350-word list instead of per character. Score thresholds in
 * bits: &lt;28 → 0, &lt;40 → 1, &lt;60 → 2, &lt;80 → 3, otherwise 4. This is a conservative
 * estimate for advice, not a guarantee.
 */
public final class LocalStrengthEstimator implements PasswordStrengthPort {

    private static final String[] KEYBOARD_ROWS = {"qwertyuiop", "asdfghjkl", "zxcvbnm", "1234567890"};
    private final char[][] words;
    private final double wordBits;

    public LocalStrengthEstimator() {
        this.words = loadWords();
        this.wordBits = Math.log(Math.max(2, words.length)) / Math.log(2) + 2; // +2 for capitalization variants
    }

    @Override
    public StrengthResult estimate(Secret password) {
        char[] c = password.chars();
        int n = c.length;
        if (n == 0) {
            return new StrengthResult(0, "", List.of("Type a password."));
        }
        boolean lower = false, upper = false, digit = false, symbol = false, other = false;
        for (char ch : c) {
            if (ch >= 'a' && ch <= 'z') {
                lower = true;
            } else if (ch >= 'A' && ch <= 'Z') {
                upper = true;
            } else if (ch >= '0' && ch <= '9') {
                digit = true;
            } else if (ch < 128) {
                symbol = true;
            } else {
                other = true;
            }
        }
        int pool = (lower ? 26 : 0) + (upper ? 26 : 0) + (digit ? 10 : 0) + (symbol ? 33 : 0) + (other ? 100 : 0);
        double bitsPerChar = Math.log(pool) / Math.log(2);

        char[] folded = fold(c);
        try {
            boolean repeats = false;
            boolean sequences = false;
            double[] weight = new double[n];
            for (int i = 0; i < n; i++) {
                weight[i] = 1.0;
                if (i > 0 && folded[i] == folded[i - 1]) {
                    weight[i] = 0.15;
                    repeats = true;
                } else if (i >= 2 && (isSequence(folded[i - 2], folded[i - 1], folded[i])
                        || isKeyboardRun(folded[i - 2], folded[i - 1], folded[i]))) {
                    weight[i] = 0.25;
                    if (i >= 1) {
                        weight[i - 1] = Math.min(weight[i - 1], 0.25);
                    }
                    sequences = true;
                }
            }
            boolean dictionary = false;
            boolean[] inWord = new boolean[n];
            double bits = 0;
            for (int i = 0; i < n; ) {
                int len = longestWordAt(folded, i);
                if (len > 0) {
                    dictionary = true;
                    bits += wordBits;
                    for (int k = i; k < i + len; k++) {
                        inWord[k] = true;
                    }
                    i += len;
                } else {
                    i++;
                }
            }
            for (int i = 0; i < n; i++) {
                if (!inWord[i]) {
                    bits += weight[i] * bitsPerChar;
                }
            }
            if (distinct(c) <= 3) {
                bits = Math.min(bits, 20);
                repeats = true;
            }
            int score = bits < 28 ? 0 : bits < 40 ? 1 : bits < 60 ? 2 : bits < 80 ? 3 : 4;

            String warning = dictionary ? "Contains a common word or password."
                    : repeats ? "Avoid repeated characters."
                    : sequences ? "Avoid sequences like abc, 1234 or qwerty." : "";
            List<String> suggestions = new ArrayList<>();
            if (n < 12) {
                suggestions.add("Use at least 12 characters.");
            }
            int classes = (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (symbol || other ? 1 : 0);
            if (classes < 3 && score < 4) {
                suggestions.add("Mix in upper case, digits or symbols.");
            }
            if (score < 3) {
                suggestions.add("Add more random characters or words.");
            }
            return new StrengthResult(score, warning, suggestions);
        } finally {
            Arrays.fill(folded, '\0');
        }
    }

    /** Lower-case and undo common l33t substitutions, into a new array the caller zeroes. */
    static char[] fold(char[] c) {
        char[] out = new char[c.length];
        for (int i = 0; i < c.length; i++) {
            char ch = Character.toLowerCase(c[i]);
            out[i] = switch (ch) {
                case '0' -> 'o';
                case '1', '!' -> 'i';
                case '3' -> 'e';
                case '4', '@' -> 'a';
                case '5', '$' -> 's';
                case '7' -> 't';
                default -> ch;
            };
        }
        return out;
    }

    private int longestWordAt(char[] folded, int start) {
        for (char[] w : words) { // sorted longest first
            if (start + w.length <= folded.length && regionMatches(folded, start, w)) {
                return w.length;
            }
        }
        return 0;
    }

    private static boolean regionMatches(char[] a, int start, char[] w) {
        for (int i = 0; i < w.length; i++) {
            if (a[start + i] != w[i]) {
                return false;
            }
        }
        return true;
    }

    static boolean isSequence(char a, char b, char c) {
        int d1 = b - a;
        int d2 = c - b;
        return d1 == d2 && Math.abs(d1) == 1 && Character.isLetterOrDigit(a) && Character.isLetterOrDigit(c);
    }

    static boolean isKeyboardRun(char a, char b, char c) {
        for (String row : KEYBOARD_ROWS) {
            int i = row.indexOf(a);
            if (i >= 0 && ((i + 2 < row.length() && row.charAt(i + 1) == b && row.charAt(i + 2) == c)
                    || (i - 2 >= 0 && row.charAt(i - 1) == b && row.charAt(i - 2) == c))) {
                return true;
            }
        }
        return false;
    }

    private static int distinct(char[] c) {
        char[] sorted = c.clone();
        try {
            Arrays.sort(sorted);
            int d = sorted.length == 0 ? 0 : 1;
            for (int i = 1; i < sorted.length; i++) {
                if (sorted[i] != sorted[i - 1]) {
                    d++;
                }
            }
            return d;
        } finally {
            Arrays.fill(sorted, '\0');
        }
    }

    private static char[][] loadWords() {
        List<char[]> list = new ArrayList<>();
        try (InputStream in = LocalStrengthEstimator.class.getResourceAsStream("/vaultlocal/common-words.txt")) {
            if (in == null) {
                return new char[0][];
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String w = line.strip().toLowerCase(Locale.ROOT);
                if (w.length() >= 4 && !w.startsWith("#")) {
                    list.add(w.toCharArray());
                }
            }
        } catch (IOException e) {
            return new char[0][];
        }
        list.sort(Comparator.comparingInt((char[] w) -> w.length).reversed());
        return list.toArray(new char[0][]);
    }
}
