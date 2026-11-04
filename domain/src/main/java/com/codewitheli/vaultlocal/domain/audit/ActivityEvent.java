package com.codewitheli.vaultlocal.domain.audit;

import com.codewitheli.vaultlocal.domain.entry.EntryId;

import java.time.Instant;
import java.util.Objects;

/**
 * One activity-log row.
 *
 * @param entryId opaque id only, or {@code null}; the UI resolves it to a label after unlock
 */
public record ActivityEvent(long id, Instant occurredAt, ActivityType type, EntryId entryId) {

    public static final int MAX_KEPT = 1000;

    public ActivityEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(type, "type");
    }
}
