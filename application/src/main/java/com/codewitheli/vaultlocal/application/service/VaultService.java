package com.codewitheli.vaultlocal.application.service;

import com.codewitheli.vaultlocal.application.keys.EntryCipher;
import com.codewitheli.vaultlocal.application.keys.KdfProfile;
import com.codewitheli.vaultlocal.application.keys.KeyHierarchy;
import com.codewitheli.vaultlocal.application.keys.Unwrapped;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.ChangeMasterKeyUseCase;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.ChangeSecurityQuestionsUseCase;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.LockVaultUseCase;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.MasterKeyHintUseCase;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.RecoverVaultUseCase;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.RecoveryTicket;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.SetupVaultUseCase;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.UnlockVaultUseCase;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.VerifyMasterKeyUseCase;
import com.codewitheli.vaultlocal.application.port.out.ClockPort;
import com.codewitheli.vaultlocal.application.port.out.EntryRecord;
import com.codewitheli.vaultlocal.application.port.out.HistoryRecord;
import com.codewitheli.vaultlocal.application.port.out.KeyWrap;
import com.codewitheli.vaultlocal.application.port.out.PasswordStrengthPort;
import com.codewitheli.vaultlocal.application.port.out.Repositories.EntryRepository;
import com.codewitheli.vaultlocal.application.port.out.Repositories.KeyWrapRepository;
import com.codewitheli.vaultlocal.application.port.out.Repositories.PasswordHistoryRepository;
import com.codewitheli.vaultlocal.application.port.out.Repositories.SecurityQuestionRepository;
import com.codewitheli.vaultlocal.application.port.out.Repositories.VaultMetaRepository;
import com.codewitheli.vaultlocal.application.port.out.UnitOfWork;
import com.codewitheli.vaultlocal.application.port.out.VaultMeta;
import com.codewitheli.vaultlocal.application.port.out.WrapType;
import com.codewitheli.vaultlocal.domain.audit.ActivityType;
import com.codewitheli.vaultlocal.domain.entry.Entry;
import com.codewitheli.vaultlocal.domain.error.ErrorCode;
import com.codewitheli.vaultlocal.domain.error.VaultException;
import com.codewitheli.vaultlocal.domain.error.WrongSecretException;
import com.codewitheli.vaultlocal.domain.policy.LockoutChannel;
import com.codewitheli.vaultlocal.domain.policy.MasterKeyPolicy;
import com.codewitheli.vaultlocal.domain.recovery.RecoveryAnswers;
import com.codewitheli.vaultlocal.domain.recovery.SecurityQuestion;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import com.codewitheli.vaultlocal.domain.secret.SecretBytes;
import com.codewitheli.vaultlocal.domain.secret.SecretCodec;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Vault lifecycle: setup, unlock/lock, verification, hint, recovery, and credential changes. */
public final class VaultService implements SetupVaultUseCase, UnlockVaultUseCase, LockVaultUseCase,
        VerifyMasterKeyUseCase, MasterKeyHintUseCase, RecoverVaultUseCase, ChangeMasterKeyUseCase,
        ChangeSecurityQuestionsUseCase {

    private final KeyHierarchy keys;
    private final KdfProfile profile;
    private final EntryCipher cipher;
    private final VaultSession session;
    private final LockoutGuard lockout;
    private final ActivityRecorder activity;
    private final PasswordStrengthPort strength;
    private final ClockPort clock;
    private final UnitOfWork uow;
    private final VaultMetaRepository metaRepo;
    private final KeyWrapRepository wrapRepo;
    private final SecurityQuestionRepository questionRepo;
    private final EntryRepository entryRepo;
    private final PasswordHistoryRepository historyRepo;

    public VaultService(KeyHierarchy keys, KdfProfile profile, EntryCipher cipher, VaultSession session,
                        LockoutGuard lockout, ActivityRecorder activity, PasswordStrengthPort strength,
                        ClockPort clock, UnitOfWork uow, VaultMetaRepository metaRepo, KeyWrapRepository wrapRepo,
                        SecurityQuestionRepository questionRepo, EntryRepository entryRepo,
                        PasswordHistoryRepository historyRepo) {
        this.keys = keys;
        this.profile = profile;
        this.cipher = cipher;
        this.session = session;
        this.lockout = lockout;
        this.activity = activity;
        this.strength = strength;
        this.clock = clock;
        this.uow = uow;
        this.metaRepo = metaRepo;
        this.wrapRepo = wrapRepo;
        this.questionRepo = questionRepo;
        this.entryRepo = entryRepo;
        this.historyRepo = historyRepo;
    }

    // ---------------------------------------------------------------- setup

    @Override
    public boolean vaultExists() {
        return metaRepo.find().isPresent();
    }

    @Override
    public void createVault(Secret masterKey, boolean recoveryEnabled, List<SecurityQuestion> questions,
                            List<Secret> answers) {
        if (vaultExists()) {
            throw new VaultException(ErrorCode.VAULT_ALREADY_EXISTS);
        }
        validateNewMasterKey(masterKey);
        if (recoveryEnabled) {
            SecurityQuestion.validateSet(questions);
        }
        Instant now = clock.now();
        VaultMeta meta = new VaultMeta(UUID.randomUUID().toString(), VaultMeta.FORMAT_VERSION,
                masterKey.codePointCount(), 1, now);
        SecretBytes dek = keys.newDek();
        try {
            KeyWrap master = wrapMaster(masterKey, dek, meta, now);
            KeyWrap recovery = recoveryEnabled ? wrapRecovery(answers, dek, meta, now) : null;
            uow.run(() -> {
                metaRepo.save(meta);
                wrapRepo.save(master);
                if (recovery != null) {
                    wrapRepo.save(recovery);
                    questionRepo.replaceAll(questions);
                }
            });
        } catch (RuntimeException e) {
            dek.close();
            throw e;
        }
        session.open(dek, meta);
        activity.record(ActivityType.VAULT_CREATED);
    }

    // ---------------------------------------------------------------- unlock / lock / verify / hint

    @Override
    public void unlock(Secret masterKey) {
        try (Unwrapped unwrapped = unwrapMaster(masterKey, ActivityType.UNLOCK_FAILED)) {
            session.open(unwrapped.dek().copy(), requireMeta());
        }
        activity.record(ActivityType.UNLOCK_OK);
    }

    @Override
    public boolean isUnlocked() {
        return session.isUnlocked();
    }

    @Override
    public Duration lockoutRemaining(LockoutChannel channel) {
        return lockout.remaining(channel);
    }

    @Override
    public void lock(boolean automatic) {
        if (session.isUnlocked()) {
            session.lock();
            activity.record(automatic ? ActivityType.AUTO_LOCKED : ActivityType.LOCKED);
        }
    }

    @Override
    public void verifyMasterKey(Secret masterKey) {
        unwrapMaster(masterKey, ActivityType.VERIFY_FAILED).close();
    }

    @Override
    public int masterKeyLength() {
        return requireMeta().masterKeyLength();
    }

    /** Checks the lockout, then derives KEK_M and unwraps; a failure is counted and logged. */
    private Unwrapped unwrapMaster(Secret masterKey, ActivityType failureEvent) {
        lockout.check(LockoutChannel.MASTER);
        VaultMeta meta = requireMeta();
        KeyWrap wrap = wrapRepo.find(WrapType.MASTER).orElseThrow(() -> new VaultException(ErrorCode.VAULT_CORRUPTED));
        Optional<Unwrapped> result;
        try (SecretBytes input = SecretCodec.utf8(masterKey)) {
            result = keys.unwrap(wrap, input, meta.vaultId(), meta.dekGeneration());
        }
        if (result.isEmpty()) {
            Duration delay = lockout.recordFailure(LockoutChannel.MASTER);
            activity.record(failureEvent);
            throw new WrongSecretException(ErrorCode.WRONG_MASTER_KEY, delay);
        }
        lockout.recordSuccess(LockoutChannel.MASTER);
        return result.get();
    }

    // ---------------------------------------------------------------- recovery

    @Override
    public boolean recoveryEnabled() {
        return wrapRepo.find(WrapType.RECOVERY).isPresent();
    }

    @Override
    public List<SecurityQuestion> securityQuestions() {
        return questionRepo.findAll();
    }

    @Override
    public RecoveryTicket verifyAnswers(List<Secret> answers) {
        lockout.check(LockoutChannel.RECOVERY);
        VaultMeta meta = requireMeta();
        KeyWrap wrap = wrapRepo.find(WrapType.RECOVERY).orElseThrow(() -> new VaultException(ErrorCode.RECOVERY_DISABLED));
        Optional<Unwrapped> result;
        try (RecoveryAnswers normalized = RecoveryAnswers.normalize(answers);
             SecretBytes input = normalized.kdfInput()) {
            result = keys.unwrap(wrap, input, meta.vaultId(), meta.dekGeneration());
        }
        if (result.isEmpty()) {
            Duration delay = lockout.recordFailure(LockoutChannel.RECOVERY);
            activity.record(ActivityType.RECOVERY_FAILED);
            throw new WrongSecretException(ErrorCode.WRONG_ANSWERS, delay);
        }
        lockout.recordSuccess(LockoutChannel.RECOVERY);
        return new Ticket(result.get(), meta, wrap);
    }

    /**
     * Decision Q1: recovery rotates the DEK. Every field and every history item is re-encrypted
     * under a new DEK and generation, the MASTER wrap is replaced (new salt), and the RECOVERY
     * wrap is resealed with the KEK already derived from the answers. Any copy of an old wrap
     * now only opens a key that encrypts nothing.
     */
    @Override
    public void completeRecovery(RecoveryTicket recoveryTicket, Secret newMasterKey) {
        Ticket ticket = (Ticket) recoveryTicket;
        try {
            validateNewMasterKey(newMasterKey);
            VaultMeta oldMeta = ticket.meta;
            VaultMeta newMeta = oldMeta.withDekGeneration(oldMeta.dekGeneration() + 1)
                    .withMasterKeyLength(newMasterKey.codePointCount());
            Instant now = clock.now();
            SecretBytes newDek = keys.newDek();
            try {
                SecretBytes oldDek = ticket.unwrapped.dek();
                List<EntryRecord> entries = reencryptEntries(oldDek, oldMeta.dekGeneration(), newDek, newMeta.dekGeneration());
                List<HistoryRecord> history = reencryptHistory(oldDek, oldMeta.dekGeneration(), newDek, newMeta.dekGeneration());
                KeyWrap master = wrapMaster(newMasterKey, newDek, newMeta, now);
                KeyWrap recovery = keys.seal(WrapType.RECOVERY, ticket.unwrapped.kek(), newDek,
                        newMeta.vaultId(), newMeta.dekGeneration(), now);
                uow.run(() -> {
                    entries.forEach(entryRepo::update);
                    history.forEach(historyRepo::replace);
                    wrapRepo.save(master);
                    wrapRepo.save(recovery);
                    metaRepo.save(newMeta);
                });
            } catch (RuntimeException e) {
                newDek.close();
                throw e;
            }
            uow.compact();
            session.open(newDek, newMeta);
            lockout.recordSuccess(LockoutChannel.MASTER);
            activity.record(ActivityType.RECOVERY_OK);
        } finally {
            ticket.close();
        }
    }

    private List<EntryRecord> reencryptEntries(SecretBytes oldDek, int oldGen, SecretBytes newDek, int newGen) {
        List<EntryRecord> out = new ArrayList<>();
        for (EntryRecord r : entryRepo.findAll()) {
            Entry e = cipher.decrypt(r, oldDek, oldGen);
            try (Secret pw = cipher.openPassword(r, oldDek, oldGen)) {
                byte[] sealed = cipher.sealPassword(newDek, pw, r.id(), newGen);
                out.add(cipher.encrypt(r.id(), e.details(), sealed, r.favorite(), r.createdAt(), r.updatedAt(),
                        r.passwordChangedAt(), newDek, newGen));
            }
        }
        return out;
    }

    private List<HistoryRecord> reencryptHistory(SecretBytes oldDek, int oldGen, SecretBytes newDek, int newGen) {
        List<HistoryRecord> out = new ArrayList<>();
        for (HistoryRecord h : historyRepo.findAll()) {
            try (Secret pw = cipher.openHistory(h, oldDek, oldGen)) {
                out.add(new HistoryRecord(h.entryId(), h.seq(),
                        cipher.sealHistory(newDek, pw, h.entryId(), h.replacedAt(), newGen), h.replacedAt()));
            }
        }
        return out;
    }

    private static final class Ticket implements RecoveryTicket {
        private final Unwrapped unwrapped;
        private final VaultMeta meta;
        private final KeyWrap recoveryWrap;

        Ticket(Unwrapped unwrapped, VaultMeta meta, KeyWrap recoveryWrap) {
            this.unwrapped = unwrapped;
            this.meta = meta;
            this.recoveryWrap = recoveryWrap;
        }

        @Override
        public void close() {
            unwrapped.close();
        }

        @Override
        public String toString() {
            return "RecoveryTicket[" + recoveryWrap.type() + "]";
        }
    }

    // ---------------------------------------------------------------- credential changes

    @Override
    public void changeMasterKey(Secret currentMasterKey, Secret newMasterKey) {
        verifyMasterKey(currentMasterKey);
        validateNewMasterKey(newMasterKey);
        Instant now = clock.now();
        session.withDek((dek, meta) -> {
            VaultMeta updated = meta.withMasterKeyLength(newMasterKey.codePointCount());
            KeyWrap master = wrapMaster(newMasterKey, dek, updated, now);
            uow.run(() -> {
                wrapRepo.save(master);
                metaRepo.save(updated);
            });
            session.updateMeta(updated);
            return null;
        });
        uow.compact();
        activity.record(ActivityType.MASTER_KEY_CHANGED);
    }

    @Override
    public void changeSecurityQuestions(Secret masterKey, List<SecurityQuestion> questions, List<Secret> answers) {
        verifyMasterKey(masterKey);
        SecurityQuestion.validateSet(questions);
        Instant now = clock.now();
        session.withDek((dek, meta) -> {
            KeyWrap recovery = wrapRecovery(answers, dek, meta, now);
            uow.run(() -> {
                wrapRepo.save(recovery);
                questionRepo.replaceAll(questions);
            });
            return null;
        });
        uow.compact();
        activity.record(ActivityType.QUESTIONS_CHANGED);
    }

    @Override
    public void turnOffRecovery(Secret masterKey) {
        verifyMasterKey(masterKey);
        uow.run(() -> {
            wrapRepo.delete(WrapType.RECOVERY);
            questionRepo.replaceAll(List.of());
        });
        uow.compact();
        activity.record(ActivityType.RECOVERY_TURNED_OFF);
    }

    // ---------------------------------------------------------------- helpers

    private void validateNewMasterKey(Secret masterKey) {
        MasterKeyPolicy.validate(masterKey, strength.estimate(masterKey).score());
    }

    private KeyWrap wrapMaster(Secret masterKey, SecretBytes dek, VaultMeta meta, Instant now) {
        try (SecretBytes input = SecretCodec.utf8(masterKey)) {
            return keys.wrapNew(WrapType.MASTER, input, profile.master(), dek, meta.vaultId(), meta.dekGeneration(), now);
        }
    }

    private KeyWrap wrapRecovery(List<Secret> answers, SecretBytes dek, VaultMeta meta, Instant now) {
        try (RecoveryAnswers normalized = RecoveryAnswers.normalize(answers)) {
            normalized.requireMinimumLength();
            try (SecretBytes input = normalized.kdfInput()) {
                return keys.wrapNew(WrapType.RECOVERY, input, profile.recovery(), dek, meta.vaultId(),
                        meta.dekGeneration(), now);
            }
        }
    }

    private VaultMeta requireMeta() {
        return metaRepo.find().orElseThrow(() -> new VaultException(ErrorCode.VAULT_NOT_SET_UP));
    }
}
