package com.codewitheli.vaultlocal.application.keys;

import com.codewitheli.vaultlocal.application.port.out.WrapType;
import com.codewitheli.vaultlocal.domain.entry.EntryId;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Additional authenticated data. Binding every blob to its vault/row/field/DEK generation means a
 * ciphertext copied to a different place fails to decrypt (DESIGN.md §4.4).
 */
public final class Aad {

    public enum Field { PLATFORM, ACCOUNT_LABEL, USERNAME, PASSWORD, WEBSITE_URL, NOTES }

    private Aad() {
    }

    public static byte[] wrap(WrapType type, String vaultId, int dekGeneration) {
        return bytes("VL1|wrap|" + type + "|" + vaultId + "|" + dekGeneration);
    }

    public static byte[] field(EntryId id, Field field, int dekGeneration) {
        return bytes("VL1|entry|" + id + "|" + field + "|" + dekGeneration);
    }

    public static byte[] history(EntryId id, Instant replacedAt, int dekGeneration) {
        return bytes("VL1|history|" + id + "|" + replacedAt.toEpochMilli() + "|" + dekGeneration);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
