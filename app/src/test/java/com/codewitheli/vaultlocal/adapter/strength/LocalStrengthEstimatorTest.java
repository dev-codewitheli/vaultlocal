package com.codewitheli.vaultlocal.adapter.strength;

import com.codewitheli.vaultlocal.application.port.out.PasswordStrengthPort.StrengthResult;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class LocalStrengthEstimatorTest {

    private final LocalStrengthEstimator estimator = new LocalStrengthEstimator();

    private StrengthResult score(String pw) {
        try (Secret s = Secret.wrap(pw.toCharArray())) {
            return estimator.estimate(s);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"aaaaaaaaaaaa", "password1234", "Password123!", "qwertyuiop12", "123456789012",
            "abcdefghijkl", "iloveyou2026", "P@ssw0rd2026", "letmeinletmein"})
    void commonOrPatternedPasswordsAreBlockedAsMasterKeys(String pw) {
        assertThat(score(pw).score()).as(pw).isLessThan(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Tr0ub4dor & 3 horses", "kX9#mQ2$vL7!pR4w", "violet-anchor-brisk-tundra-42",
            "Correct-Horse-42-Battery", "q7Vd9KmPz3XwH6tBn2FjR8Ls"})
    void strongPasswordsScoreAtLeastThree(String pw) {
        assertThat(score(pw).score()).as(pw).isGreaterThanOrEqualTo(3);
    }

    @Test
    void twelveRandomLowercaseIsFairWhichIsTheMinimumAccepted() {
        assertThat(score("qzvhnxkdwrtj").score()).isEqualTo(2);
    }

    @Test
    void feedbackIsGenericAndNeverEchoesThePassword() {
        StrengthResult r = score("password1234");
        assertThat(r.warning()).isEqualTo("Contains a common word or password.");
        assertThat(String.join(" ", r.suggestions()) + r.warning()).doesNotContain("password1234");
    }

    @Test
    void inputIsNotModified() {
        char[] raw = "Some-Pass-99!".toCharArray();
        try (Secret s = Secret.wrap(raw)) {
            estimator.estimate(s);
            assertThat(new String(s.chars())).isEqualTo("Some-Pass-99!");
        }
    }
}
