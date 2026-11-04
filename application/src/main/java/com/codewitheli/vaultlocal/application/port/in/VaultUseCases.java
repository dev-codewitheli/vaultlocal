package com.codewitheli.vaultlocal.application.port.in;

import com.codewitheli.vaultlocal.domain.audit.ActivityEvent;
import com.codewitheli.vaultlocal.domain.entry.Entry;
import com.codewitheli.vaultlocal.domain.entry.EntryDetails;
import com.codewitheli.vaultlocal.domain.entry.EntryId;
import com.codewitheli.vaultlocal.domain.entry.PasswordHistoryItem;
import com.codewitheli.vaultlocal.domain.policy.LockoutChannel;
import com.codewitheli.vaultlocal.domain.recovery.SecurityQuestion;
import com.codewitheli.vaultlocal.domain.secret.Secret;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Inbound ports (DESIGN.md §2.2). Every {@link Secret} argument stays owned by the caller;
 * every returned {@link Secret} must be closed by the caller.
 */
public final class VaultUseCases {

    private VaultUseCases() {
    }

    public interface SetupVaultUseCase {
        boolean vaultExists();

        /**
         * @param questions and {@code answers} are ignored (and may be empty) when
         *                  {@code recoveryEnabled} is false
         */
        void createVault(Secret masterKey, boolean recoveryEnabled, List<SecurityQuestion> questions, List<Secret> answers);
    }

    public interface UnlockVaultUseCase {
        void unlock(Secret masterKey);

        boolean isUnlocked();

        Duration lockoutRemaining(LockoutChannel channel);
    }

    public interface LockVaultUseCase {
        void lock(boolean automatic);
    }

    /** Re-checks the master key for reveal, copy and sensitive settings. */
    public interface VerifyMasterKeyUseCase {
        void verifyMasterKey(Secret masterKey);
    }

    public interface MasterKeyHintUseCase {
        /** The only hint there is: the master key's length in characters. */
        int masterKeyLength();
    }

    public interface RecoverVaultUseCase {
        boolean recoveryEnabled();

        List<SecurityQuestion> securityQuestions();

        /** Step 1: all three answers. Throws on wrong answers (with lockout). */
        RecoveryTicket verifyAnswers(List<Secret> answers);

        /** Step 2: rotates the DEK, replaces both wraps, and opens the vault (decision Q1). */
        void completeRecovery(RecoveryTicket ticket, Secret newMasterKey);
    }

    /** Holds key material between the two recovery steps; closing it discards that material. */
    public interface RecoveryTicket extends AutoCloseable {
        @Override
        void close();
    }

    public interface ChangeMasterKeyUseCase {
        void changeMasterKey(Secret currentMasterKey, Secret newMasterKey);
    }

    public interface ChangeSecurityQuestionsUseCase {
        void changeSecurityQuestions(Secret masterKey, List<SecurityQuestion> questions, List<Secret> answers);

        void turnOffRecovery(Secret masterKey);
    }

    public interface ManageEntriesUseCase {
        List<Entry> listEntries();

        Entry addEntry(EntryDetails details, Secret password, boolean favorite);

        /** @param newPassword {@code null} keeps the current password */
        Entry updateEntry(EntryId id, EntryDetails details, Secret newPassword, boolean favorite);

        void deleteEntry(EntryId id);

        Entry setFavorite(EntryId id, boolean favorite);
    }

    public interface RevealPasswordUseCase {
        Secret revealPassword(EntryId id, Secret masterKey);

        List<PasswordHistoryItem> passwordHistory(EntryId id);

        Secret revealHistoricPassword(EntryId id, int seq, Secret masterKey);
    }

    public interface CopyPasswordUseCase {
        Secret passwordForCopy(EntryId id, Secret masterKey);
    }

    public interface GeneratePasswordUseCase {
        Secret generatePassword(GeneratorOptions options);

        Secret generateMasterKey();

        com.codewitheli.vaultlocal.application.port.out.PasswordStrengthPort.StrengthResult strength(Secret password);
    }

    public interface SecurityReportUseCase {
        SecurityReport securityReport(int staleDays);

        /** Strength score per entry, computed in memory only and never stored. */
        Map<EntryId, Integer> strengthScores();
    }

    public interface BackupUseCase {
        byte[] exportBackup(Secret masterKey);

        ImportResult importBackup(byte[] backup, Secret backupMasterKey, ImportMode mode);
    }

    public enum ImportMode { MERGE, REPLACE }

    public record ImportResult(int imported, int skipped) {
    }

    public interface ActivityLogQuery {
        List<ActivityEvent> recentActivity(int limit);
    }

    public interface SettingsUseCase {
        int autoLockMinutes();

        void setAutoLockMinutes(int minutes);

        int staleDays();

        void setStaleDays(int days);

        Theme theme();

        void setTheme(Theme theme);
    }

    public enum Theme { SYSTEM, LIGHT, DARK }

    /** @param length 8..128 */
    public record GeneratorOptions(int length, boolean upper, boolean lower, boolean digits, boolean symbols,
                                   boolean excludeAmbiguous) {

        public static final int MIN_LENGTH = 8;
        public static final int MAX_LENGTH = 128;

        public static GeneratorOptions defaults() {
            return new GeneratorOptions(20, true, true, true, true, true);
        }
    }

    public record SecurityReport(List<Entry> weak, List<List<Entry>> reused, List<Entry> stale) {

        public boolean isClean() {
            return weak.isEmpty() && reused.isEmpty() && stale.isEmpty();
        }
    }
}
