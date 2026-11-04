package com.codewitheli.vaultlocal.application.service;

import com.codewitheli.vaultlocal.application.keys.EntryCipher;
import com.codewitheli.vaultlocal.application.keys.KdfProfile;
import com.codewitheli.vaultlocal.application.keys.KeyHierarchy;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.BackupUseCase;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.ImportMode;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.ImportResult;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.VerifyMasterKeyUseCase;
import com.codewitheli.vaultlocal.application.port.out.AeadPort;
import com.codewitheli.vaultlocal.application.port.out.ClockPort;
import com.codewitheli.vaultlocal.application.port.out.EntryRecord;
import com.codewitheli.vaultlocal.application.port.out.HistoryRecord;
import com.codewitheli.vaultlocal.application.port.out.KdfParams;
import com.codewitheli.vaultlocal.application.port.out.KeyDerivationPort;
import com.codewitheli.vaultlocal.application.port.out.RandomPort;
import com.codewitheli.vaultlocal.application.port.out.Repositories.EntryRepository;
import com.codewitheli.vaultlocal.application.port.out.Repositories.PasswordHistoryRepository;
import com.codewitheli.vaultlocal.application.port.out.UnitOfWork;
import com.codewitheli.vaultlocal.domain.audit.ActivityType;
import com.codewitheli.vaultlocal.domain.entry.Entry;
import com.codewitheli.vaultlocal.domain.entry.EntryDetails;
import com.codewitheli.vaultlocal.domain.entry.EntryId;
import com.codewitheli.vaultlocal.domain.entry.PasswordHistoryItem;
import com.codewitheli.vaultlocal.domain.entry.Platform;
import com.codewitheli.vaultlocal.domain.error.ErrorCode;
import com.codewitheli.vaultlocal.domain.error.VaultException;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import com.codewitheli.vaultlocal.domain.secret.SecretBytes;
import com.codewitheli.vaultlocal.domain.secret.SecretCodec;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code .vlbackup} files (DESIGN.md §6.6):
 * <pre>
 * "VLBK" ‖ version(1) ‖ m_kib(4) ‖ t(4) ‖ p(4) ‖ salt(16) ‖ AES-GCM(payload, AAD = header)
 * </pre>
 * The key is Argon2id(master key, fresh salt). The payload holds decrypted entries and history
 * (re-encrypted as a whole) and no key material from the vault.
 */
public final class BackupService implements BackupUseCase {

    private static final byte[] MAGIC = "VLBK".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = 4 + 1 + 12 + KeyHierarchy.SALT_BYTES;

    private final EntryCipher cipher;
    private final VaultSession session;
    private final VerifyMasterKeyUseCase verifier;
    private final KeyDerivationPort kdf;
    private final AeadPort aead;
    private final RandomPort random;
    private final KdfProfile profile;
    private final ActivityRecorder activity;
    private final ClockPort clock;
    private final UnitOfWork uow;
    private final EntryRepository entries;
    private final PasswordHistoryRepository history;

    public BackupService(EntryCipher cipher, VaultSession session, VerifyMasterKeyUseCase verifier,
                         KeyDerivationPort kdf, AeadPort aead, RandomPort random, KdfProfile profile,
                         ActivityRecorder activity, ClockPort clock, UnitOfWork uow, EntryRepository entries,
                         PasswordHistoryRepository history) {
        this.cipher = cipher;
        this.session = session;
        this.verifier = verifier;
        this.kdf = kdf;
        this.aead = aead;
        this.random = random;
        this.profile = profile;
        this.activity = activity;
        this.clock = clock;
        this.uow = uow;
        this.entries = entries;
        this.history = history;
    }

    @Override
    public byte[] exportBackup(Secret masterKey) {
        verifier.verifyMasterKey(masterKey);
        KdfParams params = profile.master();
        byte[] salt = random.strongBytes(KeyHierarchy.SALT_BYTES);
        byte[] header = header(params, salt);
        byte[] payload = session.withDek((dek, meta) -> {
            int gen = meta.dekGeneration();
            List<EntryRecord> records = entries.findAll();
            try (ZeroingBuffer out = new ZeroingBuffer()) {
                out.u32(records.size());
                for (EntryRecord r : records) {
                    Entry e = cipher.decrypt(r, dek, gen);
                    out.text(r.id().toString());
                    out.text(e.platform().encode());
                    out.text(e.accountLabel());
                    out.text(e.username());
                    try (Secret pw = cipher.openPassword(r, dek, gen)) {
                        out.secret(pw);
                    }
                    out.optionalText(e.details().websiteUrl());
                    out.optionalText(e.details().notes());
                    out.u8(r.favorite() ? 1 : 0);
                    out.i64(r.createdAt().toEpochMilli());
                    out.i64(r.updatedAt().toEpochMilli());
                    out.i64(r.passwordChangedAt().toEpochMilli());
                    List<HistoryRecord> hs = history.find(r.id());
                    out.u32(hs.size());
                    for (HistoryRecord h : hs) {
                        try (Secret old = cipher.openHistory(h, dek, gen)) {
                            out.secret(old);
                        }
                        out.i64(h.replacedAt().toEpochMilli());
                    }
                }
                return out.toByteArray();
            }
        });
        try (SecretBytes input = SecretCodec.utf8(masterKey);
             SecretBytes key = kdf.derive(input, salt, params)) {
            byte[] sealed = aead.seal(key, payload, header);
            byte[] file = Arrays.copyOf(header, header.length + sealed.length);
            System.arraycopy(sealed, 0, file, header.length, sealed.length);
            activity.record(ActivityType.BACKUP_EXPORTED);
            return file;
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    @Override
    public ImportResult importBackup(byte[] file, Secret backupMasterKey, ImportMode mode) {
        if (!session.isUnlocked()) {
            throw new VaultException(ErrorCode.VAULT_LOCKED);
        }
        if (file == null || file.length < HEADER_BYTES || !Arrays.equals(Arrays.copyOf(file, 4), MAGIC)) {
            throw new VaultException(ErrorCode.BACKUP_INVALID);
        }
        ZeroingBuffer.Reader head = new ZeroingBuffer.Reader(file, 4);
        if (head.u8() != VERSION) {
            throw new VaultException(ErrorCode.BACKUP_INVALID);
        }
        KdfParams params;
        try {
            params = KdfParams.argon2id(head.u32(), head.u32(), head.u32());
        } catch (IllegalArgumentException e) {
            throw new VaultException(ErrorCode.BACKUP_INVALID);
        }
        if (params.memoryKib() > 1024 * 1024 || params.iterations() > 64 || params.parallelism() > 16) {
            throw new VaultException(ErrorCode.BACKUP_INVALID);
        }
        byte[] header = Arrays.copyOf(file, HEADER_BYTES);
        byte[] salt = Arrays.copyOfRange(file, HEADER_BYTES - KeyHierarchy.SALT_BYTES, HEADER_BYTES);
        byte[] sealed = Arrays.copyOfRange(file, HEADER_BYTES, file.length);
        Optional<byte[]> opened;
        try (SecretBytes input = SecretCodec.utf8(backupMasterKey);
             SecretBytes key = kdf.derive(input, salt, params)) {
            opened = aead.open(key, sealed, header);
        }
        byte[] payload = opened.orElseThrow(() -> new VaultException(ErrorCode.BACKUP_WRONG_KEY));
        try {
            return restore(payload, mode);
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    private ImportResult restore(byte[] payload, ImportMode mode) {
        Instant now = clock.now();
        ImportResult result = session.withDek((dek, meta) -> uow.inTransaction(() -> {
            int gen = meta.dekGeneration();
            if (mode == ImportMode.REPLACE) {
                entries.deleteAll();
            }
            Set<EntryId> existingIds = new HashSet<>();
            Set<String> existingContent = new HashSet<>();
            for (EntryRecord r : entries.findAll()) {
                existingIds.add(r.id());
                Entry e = cipher.decrypt(r, dek, gen);
                try (Secret pw = cipher.openPassword(r, dek, gen)) {
                    existingContent.add(contentKey(e.details(), pw));
                }
            }
            ZeroingBuffer.Reader in = new ZeroingBuffer.Reader(payload, 0);
            int count = in.u32();
            int imported = 0;
            int skipped = 0;
            for (int i = 0; i < count; i++) {
                EntryId id;
                EntryDetails details;
                try {
                    id = EntryId.parse(in.text());
                    Platform platform = Platform.decode(in.text());
                    String label = in.text();
                    String username = in.text();
                    try (Secret pw = in.secret()) {
                        details = new EntryDetails(platform, label, username, in.optionalText(), in.optionalText());
                        boolean favorite = in.u8() == 1;
                        Instant created = Instant.ofEpochMilli(in.i64());
                        Instant updated = Instant.ofEpochMilli(in.i64());
                        Instant changed = Instant.ofEpochMilli(in.i64());
                        int historyCount = in.u32();
                        List<Secret> olds = new ArrayList<>();
                        List<Instant> replacedAts = new ArrayList<>();
                        try {
                            for (int h = 0; h < historyCount; h++) {
                                olds.add(in.secret());
                                replacedAts.add(Instant.ofEpochMilli(in.i64()));
                            }
                            if (existingIds.contains(id) || existingContent.contains(contentKey(details, pw))) {
                                skipped++;
                                continue;
                            }
                            entries.insert(cipher.encrypt(id, details, cipher.sealPassword(dek, pw, id, gen), favorite,
                                    created, updated, changed, dek, gen));
                            for (int h = olds.size() - 1; h >= 0; h--) {
                                history.push(id, cipher.sealHistory(dek, olds.get(h), id, replacedAts.get(h), gen),
                                        replacedAts.get(h), PasswordHistoryItem.MAX_KEPT);
                            }
                            existingIds.add(id);
                            imported++;
                        } finally {
                            olds.forEach(Secret::close);
                        }
                    }
                } catch (IllegalArgumentException e) {
                    throw new VaultException(ErrorCode.BACKUP_INVALID);
                }
            }
            if (!in.atEnd()) {
                throw new VaultException(ErrorCode.BACKUP_INVALID);
            }
            return new ImportResult(imported, skipped);
        }));
        activity.record(ActivityType.BACKUP_IMPORTED);
        if (mode == ImportMode.REPLACE) {
            uow.compact();
        }
        return result;
    }

    /** In-memory duplicate key: SHA-256 over the identifying fields and the password. */
    private static String contentKey(EntryDetails d, Secret pw) {
        try (SecretBytes utf8 = SecretCodec.utf8(pw)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(d.platform().encode().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(d.accountLabel().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(d.username().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(utf8.bytes());
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] header(KdfParams params, byte[] salt) {
        byte[] h = new byte[HEADER_BYTES];
        System.arraycopy(MAGIC, 0, h, 0, 4);
        h[4] = VERSION;
        putU32(h, 5, params.memoryKib());
        putU32(h, 9, params.iterations());
        putU32(h, 13, params.parallelism());
        System.arraycopy(salt, 0, h, 17, salt.length);
        return h;
    }

    private static void putU32(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }
}
