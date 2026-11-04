package com.codewitheli.vaultlocal.application.service;

import com.codewitheli.vaultlocal.application.port.out.ClockPort;
import com.codewitheli.vaultlocal.application.port.out.Repositories.LockoutRepository;
import com.codewitheli.vaultlocal.domain.error.LockedOutException;
import com.codewitheli.vaultlocal.domain.policy.LockoutChannel;
import com.codewitheli.vaultlocal.domain.policy.LockoutState;

import java.time.Duration;
import java.time.Instant;

/** Applies {@link LockoutState} and persists it, so delays survive a restart. */
public final class LockoutGuard {

    private final LockoutRepository repository;
    private final ClockPort clock;

    public LockoutGuard(LockoutRepository repository, ClockPort clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public synchronized Duration remaining(LockoutChannel channel) {
        Instant now = clock.now();
        LockoutState state = repository.find(channel);
        if (state.clockRolledBack(now)) {
            state = state.reanchoredAt(now);
            repository.save(state);
        }
        return state.remaining(now);
    }

    public void check(LockoutChannel channel) {
        Duration left = remaining(channel);
        if (!left.isZero()) {
            throw new LockedOutException(left);
        }
    }

    /** @return the delay the failure has just started */
    public synchronized Duration recordFailure(LockoutChannel channel) {
        LockoutState next = repository.find(channel).afterFailure(clock.now());
        repository.save(next);
        return LockoutState.delayAfter(next.consecutiveFailures());
    }

    public synchronized void recordSuccess(LockoutChannel channel) {
        if (repository.find(channel).consecutiveFailures() > 0) {
            repository.save(LockoutState.initial(channel));
        }
    }
}
