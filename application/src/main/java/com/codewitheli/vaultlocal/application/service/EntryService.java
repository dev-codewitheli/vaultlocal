package com.codewitheli.vaultlocal.application.service;

import com.codewitheli.vaultlocal.application.keys.EntryCipher;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.CopyPasswordUseCase;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.ManageEntriesUseCase;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.RevealPasswordUseCase;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.VerifyMasterKeyUseCase;
import com.codewitheli.vaultlocal.application.port.out.ClockPort;
import com.codewitheli.vaultlocal.application.port.out.EntryRecord;
import com.codewitheli.vaultlocal.application.port.out.HistoryRecord;
import com.codewitheli.vaultlocal.application.port.out.Repositories.EntryRepository;
import com.codewitheli.vaultlocal.application.port.out.Repositories.PasswordHistoryRepository;
import com.codewitheli.vaultlocal.application.port.out.UnitOfWork;
import com.codewitheli.vaultlocal.domain.audit.ActivityType;
import com.codewitheli.vaultlocal.domain.entry.Entry;
import com.codewitheli.vaultlocal.domain.entry.EntryDetails;
import com.codewitheli.vaultlocal.domain.entry.EntryId;
import com.codewitheli.vaultlocal.domain.entry.PasswordHistoryItem;
import com.codewitheli.vaultlocal.domain.entry.PasswordRules;
import com.codewitheli.vaultlocal.domain.error.ErrorCode;
import com.codewitheli.vaultlocal.domain.error.VaultException;
import com.codewitheli.vaultlocal.domain.secret.Secret;

import java.time.Instant;
import java.util.List;

/** Entry CRUD, plus reveal / copy / history, each of which re-verifies the master key. */
public final class EntryService implements ManageEntriesUseCase, RevealPasswordUseCase, CopyPasswordUseCase {

    private final EntryCipher cipher;
    private final VaultSession session;
    private final VerifyMasterKeyUseCase verifier;
    private final ActivityRecorder activity;
    private final ClockPort clock;
    private final UnitOfWork uow;
    private final EntryRepository entries;
    private final PasswordHistoryRepository history;

    public EntryService(EntryCipher cipher, VaultSession session, VerifyMasterKeyUseCase verifier,
                        ActivityRecorder activity, ClockPort clock, UnitOfWork uow, EntryRepository entries,
                        PasswordHistoryRepository history) {
        this.cipher = cipher;
        this.session = session;
        this.verifier = verifier;
        this.activity = activity;
        this.clock = clock;
        this.uow = uow;
        this.entries = entries;
        this.history = history;
    }

    @Override
    public List<Entry> listEntries() {
        return session.withDek((dek, meta) -> entries.findAll().stream()
                .map(r -> cipher.decrypt(r, dek, meta.dekGeneration()))
                .toList());
    }

    @Override
    public Entry addEntry(EntryDetails details, Secret password, boolean favorite) {
        PasswordRules.validate(password);
        Instant now = clock.now();
        EntryId id = EntryId.newId();
        Entry created = session.withDek((dek, meta) -> {
            int gen = meta.dekGeneration();
            EntryRecord record = cipher.encrypt(id, details, cipher.sealPassword(dek, password, id, gen),
                    favorite, now, now, now, dek, gen);
            entries.insert(record);
            return new Entry(id, details, favorite, now, now, now);
        });
        activity.record(ActivityType.ENTRY_CREATED, id);
        return created;
    }

    @Override
    public Entry updateEntry(EntryId id, EntryDetails details, Secret newPassword, boolean favorite) {
        if (newPassword != null) {
            PasswordRules.validate(newPassword);
        }
        Instant now = clock.now();
        Entry updated = session.withDek((dek, meta) -> uow.inTransaction(() -> {
            int gen = meta.dekGeneration();
            EntryRecord current = require(id);
            byte[] sealedPassword = current.password();
            Instant passwordChangedAt = current.passwordChangedAt();
            if (newPassword != null) {
                try (Secret old = cipher.openPassword(current, dek, gen)) {
                    if (!old.contentEquals(newPassword)) {
                        history.push(id, cipher.sealHistory(dek, old, id, now, gen), now, PasswordHistoryItem.MAX_KEPT);
                        sealedPassword = cipher.sealPassword(dek, newPassword, id, gen);
                        passwordChangedAt = now;
                    }
                }
            }
            entries.update(cipher.encrypt(id, details, sealedPassword, favorite, current.createdAt(), now,
                    passwordChangedAt, dek, gen));
            return new Entry(id, details, favorite, current.createdAt(), now, passwordChangedAt);
        }));
        activity.record(ActivityType.ENTRY_UPDATED, id);
        return updated;
    }

    @Override
    public void deleteEntry(EntryId id) {
        session.withDek((dek, meta) -> {
            require(id);
            entries.delete(id);
            return null;
        });
        activity.record(ActivityType.ENTRY_DELETED, id);
    }

    @Override
    public Entry setFavorite(EntryId id, boolean favorite) {
        return session.withDek((dek, meta) -> {
            EntryRecord current = require(id);
            entries.update(current.withFavorite(favorite));
            return cipher.decrypt(current, dek, meta.dekGeneration()).withFavorite(favorite);
        });
    }

    @Override
    public Secret revealPassword(EntryId id, Secret masterKey) {
        verifier.verifyMasterKey(masterKey);
        Secret pw = openPassword(id);
        activity.record(ActivityType.REVEAL, id);
        return pw;
    }

    @Override
    public Secret passwordForCopy(EntryId id, Secret masterKey) {
        verifier.verifyMasterKey(masterKey);
        Secret pw = openPassword(id);
        activity.record(ActivityType.COPY, id);
        return pw;
    }

    @Override
    public List<PasswordHistoryItem> passwordHistory(EntryId id) {
        session.withDek((dek, meta) -> null);
        return history.find(id).stream()
                .map(h -> new PasswordHistoryItem(h.entryId(), h.seq(), h.replacedAt()))
                .toList();
    }

    @Override
    public Secret revealHistoricPassword(EntryId id, int seq, Secret masterKey) {
        verifier.verifyMasterKey(masterKey);
        Secret pw = session.withDek((dek, meta) -> {
            HistoryRecord h = history.find(id).stream().filter(r -> r.seq() == seq).findFirst()
                    .orElseThrow(() -> new VaultException(ErrorCode.ENTRY_NOT_FOUND));
            return cipher.openHistory(h, dek, meta.dekGeneration());
        });
        activity.record(ActivityType.HISTORY_REVEAL, id);
        return pw;
    }

    private Secret openPassword(EntryId id) {
        return session.withDek((dek, meta) -> cipher.openPassword(require(id), dek, meta.dekGeneration()));
    }

    private EntryRecord require(EntryId id) {
        return entries.find(id).orElseThrow(() -> new VaultException(ErrorCode.ENTRY_NOT_FOUND));
    }
}
