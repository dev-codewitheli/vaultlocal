package com.codewitheli.vaultlocal.application;

import com.codewitheli.vaultlocal.application.keys.EntryCipher;
import com.codewitheli.vaultlocal.application.keys.KdfProfile;
import com.codewitheli.vaultlocal.application.keys.KeyHierarchy;
import com.codewitheli.vaultlocal.application.port.out.AeadPort;
import com.codewitheli.vaultlocal.application.port.out.ClockPort;
import com.codewitheli.vaultlocal.application.port.out.KeyDerivationPort;
import com.codewitheli.vaultlocal.application.port.out.PasswordStrengthPort;
import com.codewitheli.vaultlocal.application.port.out.RandomPort;
import com.codewitheli.vaultlocal.application.port.out.Repositories.ActivityLogRepository;
import com.codewitheli.vaultlocal.application.port.out.Repositories.EntryRepository;
import com.codewitheli.vaultlocal.application.port.out.Repositories.KeyWrapRepository;
import com.codewitheli.vaultlocal.application.port.out.Repositories.LockoutRepository;
import com.codewitheli.vaultlocal.application.port.out.Repositories.PasswordHistoryRepository;
import com.codewitheli.vaultlocal.application.port.out.Repositories.SecurityQuestionRepository;
import com.codewitheli.vaultlocal.application.port.out.Repositories.SettingsRepository;
import com.codewitheli.vaultlocal.application.port.out.Repositories.VaultMetaRepository;
import com.codewitheli.vaultlocal.application.port.out.UnitOfWork;
import com.codewitheli.vaultlocal.application.service.ActivityRecorder;
import com.codewitheli.vaultlocal.application.service.BackupService;
import com.codewitheli.vaultlocal.application.service.EntryService;
import com.codewitheli.vaultlocal.application.service.GeneratorService;
import com.codewitheli.vaultlocal.application.service.LockoutGuard;
import com.codewitheli.vaultlocal.application.service.ReportService;
import com.codewitheli.vaultlocal.application.service.SettingsService;
import com.codewitheli.vaultlocal.application.service.VaultService;
import com.codewitheli.vaultlocal.application.service.VaultSession;

/** Wires every use case from its outbound ports. The composition root calls {@link #create}. */
public record VaultServices(VaultSession session, VaultService vault, EntryService entries,
                            GeneratorService generator, ReportService reports, BackupService backups,
                            SettingsService settings) {

    /** The outbound ports the application needs, supplied by the adapters. */
    public record Ports(KeyDerivationPort kdf, AeadPort aead, RandomPort random, PasswordStrengthPort strength,
                        ClockPort clock, UnitOfWork unitOfWork, VaultMetaRepository meta,
                        KeyWrapRepository keyWraps, SecurityQuestionRepository questions,
                        EntryRepository entryRepository, PasswordHistoryRepository history,
                        LockoutRepository lockouts, ActivityLogRepository activityLog,
                        SettingsRepository settingsRepository) {
    }

    public static VaultServices create(Ports p, KdfProfile profile) {
        KeyHierarchy keys = new KeyHierarchy(p.kdf(), p.aead(), p.random());
        EntryCipher cipher = new EntryCipher(p.aead());
        VaultSession session = new VaultSession();
        LockoutGuard lockout = new LockoutGuard(p.lockouts(), p.clock());
        ActivityRecorder activity = new ActivityRecorder(p.activityLog(), p.clock());
        VaultService vault = new VaultService(keys, profile, cipher, session, lockout, activity, p.strength(),
                p.clock(), p.unitOfWork(), p.meta(), p.keyWraps(), p.questions(), p.entryRepository(), p.history());
        EntryService entries = new EntryService(cipher, session, vault, activity, p.clock(), p.unitOfWork(),
                p.entryRepository(), p.history());
        GeneratorService generator = new GeneratorService(p.random(), p.strength());
        ReportService reports = new ReportService(cipher, session, p.entryRepository(), p.strength(), p.clock());
        BackupService backups = new BackupService(cipher, session, vault, p.kdf(), p.aead(), p.random(), profile,
                activity, p.clock(), p.unitOfWork(), p.entryRepository(), p.history());
        SettingsService settings = new SettingsService(p.settingsRepository(), p.activityLog());
        return new VaultServices(session, vault, entries, generator, reports, backups, settings);
    }
}
