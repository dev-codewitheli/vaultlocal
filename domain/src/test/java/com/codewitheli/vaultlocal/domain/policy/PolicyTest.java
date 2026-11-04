package com.codewitheli.vaultlocal.domain.policy;

import com.codewitheli.vaultlocal.domain.error.ErrorCode;
import com.codewitheli.vaultlocal.domain.error.VaultException;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyTest {

    private static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");

    @Test
    void lockoutDelayDoublesAndCapsAtSixtySeconds() {
        assertThat(IntStream.rangeClosed(0, 9).mapToObj(LockoutState::delayAfter).map(Duration::toSeconds))
                .containsExactly(0L, 1L, 2L, 4L, 8L, 16L, 32L, 60L, 60L, 60L);
        assertThat(LockoutState.delayAfter(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void failureStartsLockoutAndSuccessResets() {
        LockoutState state = LockoutState.initial(LockoutChannel.MASTER)
                .afterFailure(T0).afterFailure(T0).afterFailure(T0);
        assertThat(state.consecutiveFailures()).isEqualTo(3);
        assertThat(state.remaining(T0)).isEqualTo(Duration.ofSeconds(4));
        assertThat(state.remaining(T0.plusSeconds(3))).isEqualTo(Duration.ofSeconds(1));
        assertThat(state.remaining(T0.plusSeconds(10))).isZero();
        assertThat(state.afterSuccess()).isEqualTo(LockoutState.initial(LockoutChannel.MASTER));
    }

    @Test
    void movingTheClockBackwardsDoesNotShortenTheLockout() {
        LockoutState state = LockoutState.initial(LockoutChannel.RECOVERY);
        for (int i = 0; i < 5; i++) {
            state = state.afterFailure(T0);
        }
        Instant earlier = T0.minus(Duration.ofHours(1));
        assertThat(state.clockRolledBack(earlier)).isTrue();
        assertThat(state.remaining(earlier)).isEqualTo(Duration.ofSeconds(16));
        assertThat(state.reanchoredAt(earlier).remaining(earlier)).isEqualTo(Duration.ofSeconds(16));
    }

    @Test
    void masterKeyNeedsTwelveCodePointsAndFairStrength() {
        try (Secret shortKey = Secret.wrap("elevenchars".toCharArray());
             Secret emojiKey = Secret.wrap("😀😀😀😀😀😀abcdef".toCharArray());
             Secret okKey = Secret.wrap("correct-horse-battery".toCharArray())) {
            assertThatThrownBy(() -> MasterKeyPolicy.validate(shortKey, 4))
                    .isInstanceOfSatisfying(VaultException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.MASTER_KEY_TOO_SHORT));
            assertThatCode(() -> MasterKeyPolicy.validate(emojiKey, 4)).doesNotThrowAnyException();
            assertThatThrownBy(() -> MasterKeyPolicy.validate(okKey, 1))
                    .isInstanceOfSatisfying(VaultException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.MASTER_KEY_TOO_WEAK));
            assertThatCode(() -> MasterKeyPolicy.validate(okKey, 2)).doesNotThrowAnyException();
        }
    }

    @Test
    void stalenessUsesDays() {
        assertThat(StalenessPolicy.isStale(T0, T0.plus(Duration.ofDays(89)), 90)).isFalse();
        assertThat(StalenessPolicy.isStale(T0, T0.plus(Duration.ofDays(90)), 90)).isTrue();
    }
}
