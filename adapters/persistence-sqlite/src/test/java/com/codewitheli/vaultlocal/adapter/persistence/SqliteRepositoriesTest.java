package com.codewitheli.vaultlocal.adapter.persistence;

import com.codewitheli.vaultlocal.adapter.crypto.AesGcmAead;
import com.codewitheli.vaultlocal.adapter.crypto.SecureRandomSource;
import com.codewitheli.vaultlocal.application.keys.EntryCipher;
import com.codewitheli.vaultlocal.application.port.out.HistoryRecord;
import com.codewitheli.vaultlocal.application.port.out.KdfParams;
import com.codewitheli.vaultlocal.application.port.out.KeyWrap;
import com.codewitheli.vaultlocal.application.port.out.VaultMeta;
import com.codewitheli.vaultlocal.application.port.out.WrapType;
import com.codewitheli.vaultlocal.domain.audit.ActivityType;
import com.codewitheli.vaultlocal.domain.entry.EntryDetails;
import com.codewitheli.vaultlocal.domain.entry.EntryId;
import com.codewitheli.vaultlocal.domain.entry.Platform;
import com.codewitheli.vaultlocal.domain.entry.PlatformKind;
import com.codewitheli.vaultlocal.domain.policy.LockoutChannel;
import com.codewitheli.vaultlocal.domain.policy.LockoutState;
import com.codewitheli.vaultlocal.domain.recovery.PredefinedQuestion;
import com.codewitheli.vaultlocal.domain.recovery.SecurityQuestion;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import com.codewitheli.vaultlocal.domain.secret.SecretBytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteRepositoriesTest {

    private static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");

    @TempDir
    Path dir;

    private Path dbFile() {
        return dir.resolve("vault.db");
    }

    @Test
    void migratesAndAppliesHardeningPragmas() {
        try (SqliteDatabase db = SqliteDatabase.open(dbFile())) {
            assertThat(db.schemaVersion()).isEqualTo(1);
            assertThat(db.pragma("journal_mode")).isEqualToIgnoringCase("delete");
            assertThat(db.pragma("secure_delete")).isEqualTo("1");
            assertThat(db.pragma("foreign_keys")).isEqualTo("1");
        }
        try (SqliteDatabase reopened = SqliteDatabase.open(dbFile())) {
            assertThat(reopened.schemaVersion()).isEqualTo(1);
        }
    }

    @Test
    void metaWrapsQuestionsAndSettingsRoundTrip() {
        try (SqliteDatabase db = SqliteDatabase.open(dbFile())) {
            SqliteRepositories repos = new SqliteRepositories(db);
            VaultMeta meta = new VaultMeta("vault-1", 1, 16, 1, T0);
            repos.vaultMeta().save(meta);
            repos.vaultMeta().save(meta.withMasterKeyLength(24));
            assertThat(repos.vaultMeta().find()).contains(meta.withMasterKeyLength(24));

            KeyWrap wrap = new KeyWrap(WrapType.MASTER, KdfParams.argon2id(1024, 1, 1), new byte[16], new byte[]{1, 2, 3}, T0);
            repos.keyWraps().save(wrap);
            KeyWrap loaded = repos.keyWraps().find(WrapType.MASTER).orElseThrow();
            assertThat(loaded.params()).isEqualTo(wrap.params());
            assertThat(loaded.wrappedDek()).containsExactly(1, 2, 3);
            assertThat(repos.keyWraps().find(WrapType.RECOVERY)).isEmpty();

            List<SecurityQuestion> questions = List.of(
                    SecurityQuestion.predefined(PredefinedQuestion.FIRST_PET),
                    SecurityQuestion.custom("Which song did we dance to?"),
                    SecurityQuestion.predefined(PredefinedQuestion.FIRST_CAR));
            repos.securityQuestions().replaceAll(questions);
            assertThat(repos.securityQuestions().findAll()).isEqualTo(questions);

            repos.settings().put("theme", "dark");
            repos.settings().put("theme", "light");
            assertThat(repos.settings().get("theme")).contains("light");
            assertThat(repos.settings().get("missing")).isEmpty();
        }
    }

    @Test
    void lockoutStatePersistsAcrossReopen() {
        LockoutState state = LockoutState.initial(LockoutChannel.MASTER).afterFailure(T0).afterFailure(T0);
        try (SqliteDatabase db = SqliteDatabase.open(dbFile())) {
            new SqliteRepositories(db).lockouts().save(state);
        }
        try (SqliteDatabase db = SqliteDatabase.open(dbFile())) {
            SqliteRepositories repos = new SqliteRepositories(db);
            assertThat(repos.lockouts().find(LockoutChannel.MASTER)).isEqualTo(state);
            assertThat(repos.lockouts().find(LockoutChannel.RECOVERY)).isEqualTo(LockoutState.initial(LockoutChannel.RECOVERY));
        }
    }

    @Test
    void historyKeepsTheFiveNewestAndCascadesOnDelete() {
        try (SqliteDatabase db = SqliteDatabase.open(dbFile())) {
            SqliteRepositories repos = new SqliteRepositories(db);
            EntryId id = EntryId.newId();
            repos.entries().insert(new com.codewitheli.vaultlocal.application.port.out.EntryRecord(
                    id, new byte[1], new byte[1], new byte[1], new byte[1], null, null, false, T0, T0, T0));
            for (int i = 1; i <= 7; i++) {
                repos.passwordHistory().push(id, new byte[]{(byte) i}, T0.plusSeconds(i), 5);
            }
            List<HistoryRecord> history = repos.passwordHistory().find(id);
            assertThat(history).extracting(HistoryRecord::seq).containsExactly(1, 2, 3, 4, 5);
            assertThat(history).extracting(h -> h.password()[0]).containsExactly((byte) 7, (byte) 6, (byte) 5, (byte) 4, (byte) 3);
            repos.entries().delete(id);
            assertThat(repos.passwordHistory().findAll()).isEmpty();
        }
    }

    @Test
    void failedTransactionRollsBack() {
        try (SqliteDatabase db = SqliteDatabase.open(dbFile())) {
            SqliteRepositories repos = new SqliteRepositories(db);
            assertThatThrownBy(() -> db.run(() -> {
                repos.settings().put("a", "1");
                throw new IllegalStateException("boom");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(repos.settings().get("a")).isEmpty();
        }
    }

    @Test
    void activityLogIsNewestFirstAndPruned() {
        try (SqliteDatabase db = SqliteDatabase.open(dbFile())) {
            SqliteRepositories repos = new SqliteRepositories(db);
            for (int i = 0; i < 10; i++) {
                repos.activityLog().append(T0.plusSeconds(i), ActivityType.UNLOCK_OK, null);
            }
            repos.activityLog().prune(3);
            assertThat(repos.activityLog().recent(100)).hasSize(3)
                    .first().satisfies(e -> assertThat(e.occurredAt()).isEqualTo(T0.plusSeconds(9)));
        }
    }

    /** DESIGN.md §8: no plaintext of any user-entered field may appear anywhere in the DB file. */
    @Test
    void noPlaintextCanaryAppearsInTheDatabaseFile() throws IOException {
        SecureRandomSource random = new SecureRandomSource();
        EntryCipher cipher = new EntryCipher(new AesGcmAead(random));
        List<String> canaries = List.of("CanaryLabel-7f3a", "canary.user-91c2@example.com",
                "CanaryPassword-Zx81!q", "https://canary-site-55e1.example", "CanaryNote-0b77", "CanaryPlatform-c3d9");
        try (SqliteDatabase db = SqliteDatabase.open(dbFile());
             SecretBytes dek = SecretBytes.wrap(random.strongBytes(32))) {
            SqliteRepositories repos = new SqliteRepositories(db);
            for (int i = 0; i < 20; i++) {
                EntryId id = EntryId.newId();
                EntryDetails details = new EntryDetails(i % 2 == 0 ? Platform.custom(canaries.get(5)) : Platform.of(PlatformKind.BANK),
                        canaries.get(0), canaries.get(1), canaries.get(3), canaries.get(4));
                try (Secret pw = Secret.wrap(canaries.get(2).toCharArray())) {
                    byte[] sealed = cipher.sealPassword(dek, pw, id, 1);
                    repos.entries().insert(cipher.encrypt(id, details, sealed, false, T0, T0, T0, dek, 1));
                    repos.passwordHistory().push(id, cipher.sealHistory(dek, pw, id, T0, 1), T0, 5);
                }
            }
        }
        for (Path file : filesIn(dir)) {
            byte[] raw = Files.readAllBytes(file);
            for (String canary : canaries) {
                for (var charset : List.of(StandardCharsets.UTF_8, StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
                    assertThat(indexOf(raw, canary.getBytes(charset)))
                            .as("%s found in %s (%s)", canary, file.getFileName(), charset).isEqualTo(-1);
                }
            }
        }
    }

    @Test
    void replacedWrapLeavesNoTraceAfterCompaction() throws IOException {
        byte[] oldWrapped = new SecureRandomSource().bytes(60);
        byte[] newWrapped = new SecureRandomSource().bytes(60);
        try (SqliteDatabase db = SqliteDatabase.open(dbFile())) {
            SqliteRepositories repos = new SqliteRepositories(db);
            repos.keyWraps().save(new KeyWrap(WrapType.MASTER, KdfParams.argon2id(1024, 1, 1), new byte[16], oldWrapped, T0));
            repos.keyWraps().save(new KeyWrap(WrapType.MASTER, KdfParams.argon2id(1024, 1, 1), new byte[16], newWrapped, T0));
            db.compact();
        }
        byte[] raw = Files.readAllBytes(dbFile());
        assertThat(indexOf(raw, oldWrapped)).isEqualTo(-1);
        assertThat(indexOf(raw, newWrapped)).isNotEqualTo(-1);
    }

    private static List<Path> filesIn(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).toList();
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
