package com.codewitheli.vaultlocal.domain.recovery;

import com.codewitheli.vaultlocal.domain.error.ErrorCode;
import com.codewitheli.vaultlocal.domain.error.VaultException;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import com.codewitheli.vaultlocal.domain.secret.SecretBytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnswerNormalizerTest {

    private static String normalize(String input) {
        try (Secret raw = Secret.wrap(input.toCharArray());
             Secret out = AnswerNormalizer.normalize(raw)) {
            return new String(out.chars());
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Fluffy|fluffy",
            "'  Fluffy  '|fluffy",
            "'New   York'|new york",
            "'New\tYork'|new york",
            "'New York'|new york",
            "'  Mr.  Whiskers  The  3rd '|mr. whiskers the 3rd",
            "'İSTANBUL'|istanbul",
            "Café|café",
            "'A-B, C!'|a-b, c!",
    })
    void appliesTrimCollapseAndLowercase(String input, String expected) {
        assertThat(normalize(input)).isEqualTo(expected);
    }

    @Test
    void accentsAndPunctuationStillMatter() {
        assertThat(normalize("Cafe")).isNotEqualTo(normalize("Café"));
        assertThat(normalize("ab.c")).isNotEqualTo(normalize("abc"));
    }

    @Test
    void whitespaceOnlyBecomesEmpty() {
        assertThat(normalize(" \t \n ")).isEmpty();
    }

    @Test
    void rulesTextMatchesTheImplementedRules() {
        assertThat(AnswerNormalizer.RULES_TEXT)
                .contains("start and end are ignored")
                .contains("count as one")
                .contains("case doesn't matter");
    }

    @Test
    void kdfInputIsLengthPrefixedSoSplitsCannotCollide() {
        byte[] first = kdfInput("ab", "c", "xyz");
        byte[] second = kdfInput("a", "bc", "xyz");
        assertThat(first).isNotEqualTo(second);
        assertThat(first).startsWith(0, 0, 0, 2, 'a', 'b', 0, 0, 0, 1, 'c');
    }

    @Test
    void kdfInputIsTheSameForEquivalentAnswers() {
        assertThat(kdfInput("Fluffy", "Rome", "Pizza")).isEqualTo(kdfInput("  fluffy ", "ROME", "pizza"));
    }

    @Test
    void shortAnswersAreRejectedAtSetup() {
        try (Secret a = Secret.wrap("ok!".toCharArray());
             Secret b = Secret.wrap("  ab  ".toCharArray());
             Secret c = Secret.wrap("fine".toCharArray());
             RecoveryAnswers answers = RecoveryAnswers.normalize(List.of(a, b, c))) {
            assertThatThrownBy(answers::requireMinimumLength)
                    .isInstanceOfSatisfying(VaultException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.ANSWER_TOO_SHORT));
        }
    }

    private static byte[] kdfInput(String a, String b, String c) {
        try (Secret sa = Secret.wrap(a.toCharArray());
             Secret sb = Secret.wrap(b.toCharArray());
             Secret sc = Secret.wrap(c.toCharArray());
             RecoveryAnswers answers = RecoveryAnswers.normalize(List.of(sa, sb, sc));
             SecretBytes input = answers.kdfInput()) {
            return input.bytes().clone();
        }
    }
}
