package com.codewitheli.vaultlocal.domain.entry;

import java.time.Instant;

/** A previous password, identified by when it was replaced. The password itself stays encrypted. */
public record PasswordHistoryItem(EntryId entryId, int seq, Instant replacedAt) {

    public static final int MAX_KEPT = 5;
}
