package com.codewitheli.vaultlocal.application.keys;

import com.codewitheli.vaultlocal.application.keys.Aad.Field;
import com.codewitheli.vaultlocal.application.port.out.AeadPort;
import com.codewitheli.vaultlocal.application.port.out.EntryRecord;
import com.codewitheli.vaultlocal.application.port.out.HistoryRecord;
import com.codewitheli.vaultlocal.domain.entry.Entry;
import com.codewitheli.vaultlocal.domain.entry.EntryDetails;
import com.codewitheli.vaultlocal.domain.entry.EntryId;
import com.codewitheli.vaultlocal.domain.entry.Platform;
import com.codewitheli.vaultlocal.domain.error.ErrorCode;
import com.codewitheli.vaultlocal.domain.error.VaultException;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import com.codewitheli.vaultlocal.domain.secret.SecretBytes;
import com.codewitheli.vaultlocal.domain.secret.SecretCodec;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

/**
 * Field-level encryption of entries: one AES-GCM seal per field, each with a fresh nonce and its
 * own AAD. Runs in the application layer so the persistence adapter never sees plaintext.
 */
public final class EntryCipher {

    private final AeadPort aead;

    public EntryCipher(AeadPort aead) {
        this.aead = aead;
    }

    public EntryRecord encrypt(EntryId id, EntryDetails details, byte[] sealedPassword, boolean favorite,
                               Instant createdAt, Instant updatedAt, Instant passwordChangedAt,
                               SecretBytes dek, int gen) {
        return new EntryRecord(id,
                sealText(dek, details.platform().encode(), id, Field.PLATFORM, gen),
                sealText(dek, details.accountLabel(), id, Field.ACCOUNT_LABEL, gen),
                sealText(dek, details.username(), id, Field.USERNAME, gen),
                sealedPassword,
                details.websiteUrl() == null ? null : sealText(dek, details.websiteUrl(), id, Field.WEBSITE_URL, gen),
                details.notes() == null ? null : sealText(dek, details.notes(), id, Field.NOTES, gen),
                favorite, createdAt, updatedAt, passwordChangedAt);
    }

    public byte[] sealPassword(SecretBytes dek, Secret password, EntryId id, int gen) {
        try (SecretBytes utf8 = SecretCodec.utf8(password)) {
            return aead.seal(dek, utf8.bytes(), Aad.field(id, Field.PASSWORD, gen));
        }
    }

    public Entry decrypt(EntryRecord r, SecretBytes dek, int gen) {
        EntryDetails details = new EntryDetails(
                Platform.decode(openText(dek, r.platform(), r.id(), Field.PLATFORM, gen)),
                openText(dek, r.accountLabel(), r.id(), Field.ACCOUNT_LABEL, gen),
                openText(dek, r.username(), r.id(), Field.USERNAME, gen),
                r.websiteUrl() == null ? null : openText(dek, r.websiteUrl(), r.id(), Field.WEBSITE_URL, gen),
                r.notes() == null ? null : openText(dek, r.notes(), r.id(), Field.NOTES, gen));
        return new Entry(r.id(), details, r.favorite(), r.createdAt(), r.updatedAt(), r.passwordChangedAt());
    }

    public Secret openPassword(EntryRecord r, SecretBytes dek, int gen) {
        return openSecret(dek, r.password(), Aad.field(r.id(), Field.PASSWORD, gen));
    }

    public byte[] sealHistory(SecretBytes dek, Secret password, EntryId id, Instant replacedAt, int gen) {
        try (SecretBytes utf8 = SecretCodec.utf8(password)) {
            return aead.seal(dek, utf8.bytes(), Aad.history(id, replacedAt, gen));
        }
    }

    public Secret openHistory(HistoryRecord h, SecretBytes dek, int gen) {
        return openSecret(dek, h.password(), Aad.history(h.entryId(), h.replacedAt(), gen));
    }

    private Secret openSecret(SecretBytes dek, byte[] blob, byte[] aad) {
        byte[] plain = aead.open(dek, blob, aad).orElseThrow(() -> new VaultException(ErrorCode.VAULT_CORRUPTED));
        try {
            return SecretCodec.fromUtf8(plain);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private byte[] sealText(SecretBytes dek, String text, EntryId id, Field field, int gen) {
        return aead.seal(dek, text.getBytes(StandardCharsets.UTF_8), Aad.field(id, field, gen));
    }

    private String openText(SecretBytes dek, byte[] blob, EntryId id, Field field, int gen) {
        byte[] plain = aead.open(dek, blob, Aad.field(id, field, gen))
                .orElseThrow(() -> new VaultException(ErrorCode.VAULT_CORRUPTED));
        try {
            return new String(plain, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }
}
