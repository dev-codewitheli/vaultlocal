package com.codewitheli.vaultlocal.application.port.out;

import java.time.Instant;

public interface ClockPort {

    Instant now();

    /** Monotonic, for measuring elapsed time; unaffected by wall-clock changes. */
    long monotonicNanos();

    ClockPort SYSTEM = new ClockPort() {
        @Override
        public Instant now() {
            return Instant.now();
        }

        @Override
        public long monotonicNanos() {
            return System.nanoTime();
        }
    };
}
