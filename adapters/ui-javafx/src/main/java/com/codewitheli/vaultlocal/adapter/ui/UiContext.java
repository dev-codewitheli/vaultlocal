package com.codewitheli.vaultlocal.adapter.ui;

import com.codewitheli.vaultlocal.adapter.ui.os.ClipboardService;
import com.codewitheli.vaultlocal.adapter.ui.os.SessionLockSource;
import com.codewitheli.vaultlocal.application.VaultServices;
import com.codewitheli.vaultlocal.application.port.out.ClockPort;

import java.time.Duration;

/**
 * Everything the UI needs, built by the composition root.
 *
 * @param sessionLock OS "session locked / going to sleep" signals; may be {@link SessionLockSource#NONE}
 */
public record UiContext(VaultServices services, Worker worker, ClipboardService clipboard, ClockPort clock,
                        SessionLockSource sessionLock, Timings timings) {

    /** Durations from DESIGN.md; tests shorten them. */
    public record Timings(Duration reveal, Duration clipboardClear, Duration autoLockTick, Duration sleepGap) {

        public static final Timings DEFAULT = new Timings(Duration.ofSeconds(15), Duration.ofSeconds(20),
                Duration.ofSeconds(2), Duration.ofSeconds(30));
    }
}
