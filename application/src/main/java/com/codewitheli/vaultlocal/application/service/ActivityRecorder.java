package com.codewitheli.vaultlocal.application.service;

import com.codewitheli.vaultlocal.application.port.out.ClockPort;
import com.codewitheli.vaultlocal.application.port.out.Repositories.ActivityLogRepository;
import com.codewitheli.vaultlocal.domain.audit.ActivityEvent;
import com.codewitheli.vaultlocal.domain.audit.ActivityType;
import com.codewitheli.vaultlocal.domain.entry.EntryId;

/** Appends to the local activity log. Only event types, times and opaque entry ids are recorded. */
public final class ActivityRecorder {

    private static final int PRUNE_EVERY = 50;

    private final ActivityLogRepository repository;
    private final ClockPort clock;
    private int sincePrune;

    public ActivityRecorder(ActivityLogRepository repository, ClockPort clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public void record(ActivityType type) {
        record(type, null);
    }

    public synchronized void record(ActivityType type, EntryId entryId) {
        repository.append(clock.now(), type, entryId);
        if (++sincePrune >= PRUNE_EVERY) {
            repository.prune(ActivityEvent.MAX_KEPT);
            sincePrune = 0;
        }
    }
}
