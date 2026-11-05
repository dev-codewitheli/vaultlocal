package com.codewitheli.vaultlocal.application.port.out;

import com.codewitheli.vaultlocal.domain.entry.EntryId;

import java.time.Instant;

/**
 * An entry as persisted: every user-entered field is an AES-GCM blob. The persistence adapter
 * only ever sees this type, never plaintext.
 *
 * @param websiteUrl {@code null} when absent
 * @param notes      {@code null} when absent
 */
public record EntryRecord(EntryId id, byte[] platform, byte[] accountLabel, byte[] username,
                          byte[] password, byte[] websiteUrl, byte[] notes, boolean favorite,
                          Instant createdAt, Instant updatedAt, Instant passwordChangedAt) {

    public EntryRecord withFavorite(boolean value) {
        return new EntryRecord(id, platform, accountLabel, username, password, websiteUrl, notes,
                value, createdAt, updatedAt, passwordChangedAt);
    }

    @Override
    public String toString() {
        return "EntryRecord[" + id + "]";
    }
}
