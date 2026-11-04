package com.codewitheli.vaultlocal.domain.entry;

import java.time.Instant;
import java.util.Objects;

/**
 * A decrypted entry as shown in the list. It deliberately has no password: passwords stay
 * encrypted in memory and are decrypted only for a reveal or a copy.
 */
public record Entry(EntryId id, EntryDetails details, boolean favorite,
                    Instant createdAt, Instant updatedAt, Instant passwordChangedAt) {

    public Entry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(details, "details");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(passwordChangedAt, "passwordChangedAt");
    }

    public Platform platform() {
        return details.platform();
    }

    public String accountLabel() {
        return details.accountLabel();
    }

    public String username() {
        return details.username();
    }

    public Entry withFavorite(boolean value) {
        return new Entry(id, details, value, createdAt, updatedAt, passwordChangedAt);
    }
}
