package com.codewitheli.vaultlocal.application;

import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.GeneratorOptions;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.ImportMode;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.ImportResult;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.RecoveryTicket;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.SecurityReport;
import com.codewitheli.vaultlocal.adapter.persistence.SqliteRepositories;
import com.codewitheli.vaultlocal.application.port.out.KeyWrap;
import com.codewitheli.vaultlocal.application.port.out.WrapType;
import com.codewitheli.vaultlocal.domain.audit.ActivityEvent;
import com.codewitheli.vaultlocal.domain.audit.ActivityType;
import com.codewitheli.vaultlocal.domain.entry.Entry;
import com.codewitheli.vaultlocal.domain.entry.EntryDetails;
import com.codewitheli.vaultlocal.domain.entry.Platform;
import com.codewitheli.vaultlocal.domain.entry.PlatformKind;
import com.codewitheli.vaultlocal.domain.error.ErrorCode;
import com.codewitheli.vaultlocal.domain.error.LockedOutException;
import com.codewitheli.vaultlocal.domain.error.VaultException;
import com.codewitheli.vaultlocal.domain.error.WrongSecretException;
import com.codewitheli.vaultlocal.domain.policy.LockoutChannel;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static com.codewitheli.vaultlocal.application.TestVault.s;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VaultFlowsTest {

    private static final String MASTER = "Tr0ub4dor & 3 horses";
    private static final String[] ANSWERS = {"Fluffy", "Metallica", "Ficus Benjamina"};

    @TempDir
    Path dir;

    private TestVault vault;

    @BeforeEach
    void setUp() {
        vault = TestVault.in(dir);
        vault.create(MASTER, ANSWERS);
    }

    @AfterEach
    void tearDown() {
        vault.close();
    }

    // ------------------------------------------------------------ setup & unlock

    @Test
    void setupOpensTheVaultAndStoresOnlyTheLengthForTheHint() {
        assertThat(vault.services.vault().vaultExists()).isTrue();
        assertThat(vault.services.vault().isUnlocked()).isTrue();
        assertThat(vault.services.vault().masterKeyLength()).isEqualTo(MASTER.length());
        assertThat(vault.services.vault().recoveryEnabled()).isTrue();
        assertThat(vault.services.vault().securityQuestions()).isEqualTo(TestVault.QUESTIONS);
    }

    @Test
    void weakOrShortMasterKeysAreRejectedAtSetup(@TempDir Path other) {
        try (TestVault fresh = TestVault.in(other)) {
            assertThatThrownBy(() -> fresh.create("aaaaaaaaaaaaaaaa", ANSWERS))
                    .isInstanceOfSatisfying(VaultException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.MASTER_KEY_TOO_WEAK));
            assertThatThrownBy(() -> fresh.create("Short1!", ANSWERS))
                    .isInstanceOfSatisfying(VaultException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.MASTER_KEY_TOO_SHORT));
            assertThatThrownBy(() -> fresh.create(MASTER, "ok", "Metallica", "Ficus"))
                    .isInstanceOfSatisfying(VaultException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ANSWER_TOO_SHORT));
            assertThat(fresh.services.vault().vaultExists()).isFalse();
        }
    }

    @Test
    void setupWithoutRecoveryHasNoRecoveryWrap(@TempDir Path other) {
        try (TestVault fresh = TestVault.in(other); Secret mk = s(MASTER)) {
            fresh.services.vault().createVault(mk, false, List.of(), List.of());
            assertThat(fresh.services.vault().recoveryEnabled()).isFalse();
            try (Secret a = s("Fluffy"); Secret b = s("x1x"); Secret c = s("y1y")) {
                assertThatThrownBy(() -> fresh.services.vault().verifyAnswers(List.of(a, b, c)))
                        .isInstanceOfSatisfying(VaultException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.RECOVERY_DISABLED));
            }
        }
    }

    @Test
    void wrongMasterKeyGivesProgressiveDelayThatSurvivesRestart() {
        vault.services.vault().lock(false);
        int[] expected = {1, 2, 4, 8};
        for (int delay : expected) {
            try (Secret wrong = s("not the key at all")) {
                assertThatThrownBy(() -> vault.services.vault().unlock(wrong))
                        .isInstanceOfSatisfying(WrongSecretException.class,
                                e -> assertThat(e.lockout()).isEqualTo(Duration.ofSeconds(delay)));
            }
            if (delay < 8) {
                vault.clock.advance(Duration.ofSeconds(delay));
            }
        }
        // Locked: even the right key is refused until the delay has passed.
        try (Secret right = s(MASTER)) {
            assertThatThrownBy(() -> vault.services.vault().unlock(right)).isInstanceOf(LockedOutException.class);
        }
        vault.clock.advance(Duration.ofSeconds(3));

        vault.restart();
        assertThat(vault.services.vault().lockoutRemaining(LockoutChannel.MASTER)).isEqualTo(Duration.ofSeconds(5));
        try (Secret right = s(MASTER)) {
            assertThatThrownBy(() -> vault.services.vault().unlock(right))
                    .isInstanceOfSatisfying(LockedOutException.class,
                            e -> assertThat(e.remaining()).isEqualTo(Duration.ofSeconds(5)));
            vault.clock.advance(Duration.ofSeconds(5));
            vault.services.vault().unlock(right);
        }
        assertThat(vault.services.vault().isUnlocked()).isTrue();
        assertThat(vault.services.vault().lockoutRemaining(LockoutChannel.MASTER)).isZero();
        assertThat(vault.services.settings().recentActivity(20)).extracting(ActivityEvent::type)
                .contains(ActivityType.UNLOCK_FAILED, ActivityType.UNLOCK_OK);
    }

    @Test
    void settingTheClockBackDoesNotEndTheLockout() {
        vault.services.vault().lock(false);
        try (Secret wrong = s("not the key at all")) {
            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> vault.services.vault().unlock(wrong)).isInstanceOf(WrongSecretException.class);
                vault.clock.advance(Duration.ofSeconds(60));
            }
        }
        // 6th failure would be 32 s; we're at "5 minutes later" with failures=5 (16 s, already over).
        assertThatThrownBy(() -> {
            try (Secret wrong = s("still wrong!!")) {
                vault.services.vault().unlock(wrong);
            }
        }).isInstanceOf(WrongSecretException.class);
        vault.clock.advance(Duration.ofDays(-1));
        assertThat(vault.services.vault().lockoutRemaining(LockoutChannel.MASTER)).isEqualTo(Duration.ofSeconds(32));
    }

    @Test
    void lockingZeroesTheSessionAndBlocksAccess() {
        vault.services.vault().lock(false);
        assertThat(vault.services.vault().isUnlocked()).isFalse();
        assertThatThrownBy(() -> vault.services.entries().listEntries())
                .isInstanceOfSatisfying(VaultException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VAULT_LOCKED));
    }

    // ------------------------------------------------------------ entries, reveal, history

    @Test
    void entryCrudRevealAndHistory() {
        Entry gmail = add(PlatformKind.GMAIL, "Personal", "me@example.com", "first-Password-1");
        add(PlatformKind.BANK, "Savings", "acct-holder", "bank-Password-2");
        assertThat(vault.services.entries().listEntries()).hasSize(2);

        EntryDetails edited = new EntryDetails(Platform.of(PlatformKind.GMAIL), "Personal mail", "me@example.com",
                "mail.google.com", "recovery phone on file");
        vault.clock.advance(Duration.ofMinutes(1));
        Entry notesOnly = vault.services.entries().updateEntry(gmail.id(), edited, null, false);
        assertThat(notesOnly.passwordChangedAt()).isEqualTo(gmail.passwordChangedAt());
        assertThat(notesOnly.updatedAt()).isAfter(gmail.updatedAt());

        for (int i = 2; i <= 8; i++) {
            vault.clock.advance(Duration.ofMinutes(1));
            try (Secret pw = s("rotated-Password-" + i)) {
                vault.services.entries().updateEntry(gmail.id(), edited, pw, true);
            }
        }
        assertThat(vault.services.entries().passwordHistory(gmail.id())).hasSize(5);
        try (Secret mk = s(MASTER);
             Secret current = vault.services.entries().revealPassword(gmail.id(), mk);
             Secret previous = vault.services.entries().revealHistoricPassword(gmail.id(), 1, mk)) {
            assertThat(new String(current.chars())).isEqualTo("rotated-Password-8");
            assertThat(new String(previous.chars())).isEqualTo("rotated-Password-7");
        }

        Entry fav = vault.services.entries().setFavorite(gmail.id(), false);
        assertThat(fav.favorite()).isFalse();
        vault.services.entries().deleteEntry(gmail.id());
        assertThat(vault.services.entries().listEntries()).extracting(Entry::accountLabel).containsExactly("Savings");
    }

    @Test
    void revealAndCopyRequireTheMasterKey() {
        Entry e = add(PlatformKind.X, "Main", "@me", "x-Password-9");
        try (Secret wrong = s("definitely wrong key")) {
            assertThatThrownBy(() -> vault.services.entries().revealPassword(e.id(), wrong))
                    .isInstanceOf(WrongSecretException.class);
            vault.clock.advance(Duration.ofSeconds(1));
            assertThatThrownBy(() -> vault.services.entries().passwordForCopy(e.id(), wrong))
                    .isInstanceOfSatisfying(WrongSecretException.class,
                            ex -> assertThat(ex.lockout()).isEqualTo(Duration.ofSeconds(2)));
        }
        vault.clock.advance(Duration.ofSeconds(2));
        try (Secret mk = s(MASTER); Secret pw = vault.services.entries().passwordForCopy(e.id(), mk)) {
            assertThat(new String(pw.chars())).isEqualTo("x-Password-9");
        }
        assertThat(vault.services.settings().recentActivity(10)).extracting(ActivityEvent::type)
                .contains(ActivityType.VERIFY_FAILED, ActivityType.COPY);
    }

    // ------------------------------------------------------------ recovery

    @Test
    void recoveryRotatesTheDekAndFullyReplacesTheOldMasterWrap() throws Exception {
        Entry e = add(PlatformKind.FACEBOOK, "Family", "me@fb.example", "fb-Password-1");
        try (Secret pw = s("fb-Password-2")) {
            vault.services.entries().updateEntry(e.id(), e.details(), pw, false);
        }
        SqliteRepositories repos = new SqliteRepositories(vault.db);
        KeyWrap oldMaster = repos.keyWraps().find(WrapType.MASTER).orElseThrow();
        byte[] oldEntryPassword = repos.entries().find(e.id()).orElseThrow().password();
        vault.services.vault().lock(false);

        try (Secret a = s("  FLUFFY "); Secret b = s("metallica"); Secret c = s("ficus   benjamina");
             RecoveryTicket ticket = vault.services.vault().verifyAnswers(List.of(a, b, c));
             Secret newKey = s("brand new master key 2026")) {
            vault.services.vault().completeRecovery(ticket, newKey);
        }
        assertThat(vault.services.vault().isUnlocked()).isTrue();
        assertThat(vault.services.vault().masterKeyLength()).isEqualTo("brand new master key 2026".length());

        vault.restart();
        try (Secret old = s(MASTER)) {
            assertThatThrownBy(() -> vault.services.vault().unlock(old)).isInstanceOf(WrongSecretException.class);
        }
        vault.clock.advance(Duration.ofSeconds(1));
        try (Secret newKey = s("brand new master key 2026")) {
            vault.services.vault().unlock(newKey);
            try (Secret pw = vault.services.entries().revealPassword(e.id(), newKey);
                 Secret old = vault.services.entries().revealHistoricPassword(e.id(), 1, newKey)) {
                assertThat(new String(pw.chars())).isEqualTo("fb-Password-2");
                assertThat(new String(old.chars())).isEqualTo("fb-Password-1");
            }
        }

        byte[] file = Files.readAllBytes(vault.file);
        assertThat(indexOf(file, oldMaster.wrappedDek())).as("old MASTER wrap still on disk").isEqualTo(-1);
        assertThat(indexOf(file, oldEntryPassword)).as("old-DEK ciphertext still on disk").isEqualTo(-1);
        assertThat(new SqliteRepositories(vault.db).keyWraps().find(WrapType.MASTER).orElseThrow().salt())
                .isNotEqualTo(oldMaster.salt());

        // The old answers still work for the next recovery (the recovery wrap was resealed).
        vault.services.vault().lock(false);
        try (Secret a = s("fluffy"); Secret b = s("Metallica"); Secret c = s("Ficus Benjamina")) {
            vault.services.vault().verifyAnswers(List.of(a, b, c)).close();
        }
    }

    @Test
    void wrongAnswersUseTheirOwnLockoutChannel() {
        vault.services.vault().lock(false);
        try (Secret a = s("Fluffy"); Secret b = s("Metallica"); Secret c = s("wrong plant")) {
            assertThatThrownBy(() -> vault.services.vault().verifyAnswers(List.of(a, b, c)))
                    .isInstanceOfSatisfying(WrongSecretException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.WRONG_ANSWERS);
                        assertThat(e.lockout()).isEqualTo(Duration.ofSeconds(1));
                    });
            assertThatThrownBy(() -> vault.services.vault().verifyAnswers(List.of(a, b, c)))
                    .isInstanceOf(LockedOutException.class);
        }
        assertThat(vault.services.vault().lockoutRemaining(LockoutChannel.MASTER)).isZero();
    }

    // ------------------------------------------------------------ credential changes

    @Test
    void changeMasterKeyAndSecurityQuestions() {
        try (Secret current = s(MASTER); Secret next = s("a different master key")) {
            vault.services.vault().changeMasterKey(current, next);
        }
        vault.services.vault().lock(false);
        try (Secret next = s("a different master key")) {
            vault.services.vault().unlock(next);
            try (Secret a = s("Rex"); Secret b = s("Coldplay"); Secret c = s("Monstera")) {
                vault.services.vault().changeSecurityQuestions(next, TestVault.QUESTIONS, List.of(a, b, c));
            }
        }
        vault.services.vault().lock(false);
        try (Secret a = s("Fluffy"); Secret b = s("Metallica"); Secret c = s("Ficus Benjamina")) {
            assertThatThrownBy(() -> vault.services.vault().verifyAnswers(List.of(a, b, c)))
                    .isInstanceOf(WrongSecretException.class);
        }
        vault.clock.advance(Duration.ofSeconds(1));
        try (Secret a = s("rex"); Secret b = s("COLDPLAY"); Secret c = s("monstera")) {
            vault.services.vault().verifyAnswers(List.of(a, b, c)).close();
        }
        try (Secret next = s("a different master key")) {
            vault.services.vault().unlock(next);
            vault.services.vault().turnOffRecovery(next);
        }
        assertThat(vault.services.vault().recoveryEnabled()).isFalse();
        assertThat(vault.services.vault().securityQuestions()).isEmpty();
    }

    // ------------------------------------------------------------ optional features

    @Test
    void backupRoundTripsIntoAnotherVaultAndMergeSkipsDuplicates(@TempDir Path other) {
        Entry e = add(PlatformKind.INSTAGRAM, "Art account", "@sketches", "ig-Password-1");
        try (Secret pw = s("ig-Password-2")) {
            vault.services.entries().updateEntry(e.id(), e.details(), pw, true);
        }
        add(PlatformKind.TIKTOK, "Dance", "@moves", "tt-Password-1");
        byte[] backup;
        try (Secret mk = s(MASTER)) {
            backup = vault.services.backups().exportBackup(mk);
        }
        assertThat(new String(backup, 0, 4)).isEqualTo("VLBK");
        assertThat(indexOf(backup, "ig-Password-2".getBytes())).isEqualTo(-1);
        assertThat(indexOf(backup, "Art account".getBytes())).isEqualTo(-1);

        try (TestVault target = TestVault.in(other)) {
            target.create("target vault master key", ANSWERS);
            try (Secret wrong = s("target vault master key")) {
                assertThatThrownBy(() -> target.services.backups().importBackup(backup, wrong, ImportMode.MERGE))
                        .isInstanceOfSatisfying(VaultException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.BACKUP_WRONG_KEY));
            }
            byte[] tampered = backup.clone();
            tampered[tampered.length - 1] ^= 1;
            try (Secret mk = s(MASTER)) {
                assertThatThrownBy(() -> target.services.backups().importBackup(tampered, mk, ImportMode.MERGE))
                        .isInstanceOf(VaultException.class);
                assertThat(target.services.backups().importBackup(backup, mk, ImportMode.MERGE))
                        .isEqualTo(new ImportResult(2, 0));
                assertThat(target.services.backups().importBackup(backup, mk, ImportMode.MERGE))
                        .isEqualTo(new ImportResult(0, 2));
            }
            try (Secret targetKey = s("target vault master key");
                 Secret pw = target.services.entries().revealPassword(e.id(), targetKey);
                 Secret old = target.services.entries().revealHistoricPassword(e.id(), 1, targetKey)) {
                assertThat(new String(pw.chars())).isEqualTo("ig-Password-2");
                assertThat(new String(old.chars())).isEqualTo("ig-Password-1");
            }
            try (Secret mk = s(MASTER)) {
                assertThat(target.services.backups().importBackup(backup, mk, ImportMode.REPLACE))
                        .isEqualTo(new ImportResult(2, 0));
            }
            assertThat(target.services.entries().listEntries()).hasSize(2);
        }
    }

    @Test
    void securityReportFindsWeakReusedAndStale() {
        add(PlatformKind.GMAIL, "A", "a", "aaaa");
        add(PlatformKind.GMAIL, "B", "b", "Shared-Password-1");
        add(PlatformKind.BANK, "C", "c", "Shared-Password-1");
        vault.clock.advance(Duration.ofDays(100));
        add(PlatformKind.X, "D", "d", "Fresh-Password-9");
        SecurityReport report = vault.services.reports().securityReport(90);
        assertThat(report.weak()).extracting(Entry::accountLabel).containsExactly("A");
        assertThat(report.reused()).hasSize(1).first().satisfies(g ->
                assertThat(g).extracting(Entry::accountLabel).containsExactly("B", "C"));
        assertThat(report.stale()).extracting(Entry::accountLabel).containsExactly("A", "B", "C");
        assertThat(vault.services.reports().strengthScores()).hasSize(4);
    }

    @Test
    void generatorHonoursOptions() {
        var gen = vault.services.generator();
        try (Secret pw = gen.generatePassword(new GeneratorOptions(40, true, true, true, true, true));
             Secret digitsOnly = gen.generatePassword(new GeneratorOptions(8, false, false, true, false, false));
             Secret mk = gen.generateMasterKey()) {
            String p = new String(pw.chars());
            assertThat(p).hasSize(40).containsPattern("[A-Z]").containsPattern("[a-z]").containsPattern("[0-9]")
                    .containsPattern("[^A-Za-z0-9]").doesNotContainPattern("[0Oo1lI|]");
            assertThat(new String(digitsOnly.chars())).matches("[0-9]{8}");
            assertThat(new String(mk.chars())).hasSize(24).matches("[A-HJ-NP-Za-km-np-z2-9]{24}");
        }
    }

    @Test
    void settingsHaveSafeDefaultsAndClamp() {
        var settings = vault.services.settings();
        assertThat(settings.autoLockMinutes()).isEqualTo(5);
        assertThat(settings.staleDays()).isEqualTo(90);
        settings.setAutoLockMinutes(500);
        assertThat(settings.autoLockMinutes()).isEqualTo(60);
    }

    // ------------------------------------------------------------ helpers

    private Entry add(PlatformKind kind, String label, String user, String password) {
        try (Secret pw = s(password)) {
            return vault.services.entries().addEntry(new EntryDetails(Platform.of(kind), label, user, null, null), pw, false);
        }
    }

    static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
