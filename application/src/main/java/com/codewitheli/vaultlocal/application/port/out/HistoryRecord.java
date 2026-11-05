package com.codewitheli.vaultlocal.application.port.out;

import com.codewitheli.vaultlocal.domain.entry.EntryId;

import java.time.Instant;

/** A previous password, sealed with the history AAD (bound to entry id and replacedAt). */
public record HistoryRecord(EntryId entryId, int seq, byte[] password, Instant replacedAt) {

    @Override
    public String toString() {
        return "HistoryRecord[" + entryId + ", " + seq + "]";
    }
}
