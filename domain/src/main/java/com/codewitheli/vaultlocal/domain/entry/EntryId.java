package com.codewitheli.vaultlocal.domain.entry;

import java.util.Objects;
import java.util.UUID;

/** Random, opaque entry identifier. Safe to store in plaintext and to log. */
public record EntryId(UUID value) {

    public EntryId {
        Objects.requireNonNull(value, "value");
    }

    public static EntryId newId() {
        return new EntryId(UUID.randomUUID());
    }

    public static EntryId parse(String text) {
        return new EntryId(UUID.fromString(text));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
