package com.codewitheli.vaultlocal.app;

import com.codewitheli.vaultlocal.adapter.crypto.AesGcmAead;
import com.codewitheli.vaultlocal.adapter.crypto.Argon2idKdf;
import com.codewitheli.vaultlocal.adapter.crypto.SecureRandomSource;
import com.codewitheli.vaultlocal.adapter.persistence.SqliteDatabase;
import com.codewitheli.vaultlocal.adapter.persistence.SqliteRepositories;
import com.codewitheli.vaultlocal.adapter.strength.LocalStrengthEstimator;
import com.codewitheli.vaultlocal.adapter.ui.UiErrors;
import com.codewitheli.vaultlocal.application.VaultServices;
import com.codewitheli.vaultlocal.application.keys.KdfProfile;
import com.codewitheli.vaultlocal.application.port.out.ClockPort;
import com.codewitheli.vaultlocal.domain.entry.EntryDetails;
import com.codewitheli.vaultlocal.domain.entry.Platform;
import com.codewitheli.vaultlocal.domain.entry.PlatformKind;
import com.codewitheli.vaultlocal.domain.error.VaultException;
import com.codewitheli.vaultlocal.domain.recovery.PredefinedQuestion;
import com.codewitheli.vaultlocal.domain.recovery.SecurityQuestion;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DESIGN.md §8 "logs": exercise the real flows (and error paths) with canary secrets, using the
 * production logging setup, then assert no canary appears in any log file.
 */
class NoSecretsInLogsTest {

    private static final String MASTER = "CanaryMasterKey-4471-zz";
    private static final String WRONG = "CanaryWrongKey-9134-yy";
    private static final String PASSWORD = "CanaryEntryPassword-5520";
    private static final String ANSWER = "CanaryAnswer-8812";
    private static final String LABEL = "CanaryLabel-3301";

    @TempDir
    Path dir;

    @Test
    void noCanaryReachesTheLogFiles() throws Exception {
        Path logs = AppPaths.ensure(dir.resolve("logs"));
        Logging.configure(logs);
        try (SqliteDatabase db = SqliteDatabase.open(dir.resolve("vault.db"))) {
            SqliteRepositories repos = new SqliteRepositories(db);
            SecureRandomSource random = new SecureRandomSource();
            VaultServices s = VaultServices.create(new VaultServices.Ports(new Argon2idKdf(), new AesGcmAead(random),
                    random, new LocalStrengthEstimator(), ClockPort.SYSTEM, db, repos.vaultMeta(), repos.keyWraps(),
                    repos.securityQuestions(), repos.entries(), repos.passwordHistory(), repos.lockouts(),
                    repos.activityLog(), repos.settings()), KdfProfile.FOR_TESTS);

            try (Secret mk = sec(MASTER); Secret a = sec(ANSWER + "1"); Secret b = sec(ANSWER + "2"); Secret c = sec(ANSWER + "3")) {
                s.vault().createVault(mk, true, List.of(
                        SecurityQuestion.predefined(PredefinedQuestion.FIRST_PET),
                        SecurityQuestion.predefined(PredefinedQuestion.FIRST_CAR),
                        SecurityQuestion.predefined(PredefinedQuestion.FIRST_BOOK)), List.of(a, b, c));
            }
            try (Secret pw = sec(PASSWORD)) {
                s.entries().addEntry(new EntryDetails(Platform.of(PlatformKind.BANK), LABEL, "user", null, null), pw, false);
            }
            s.vault().lock(false);
            try (Secret wrong = sec(WRONG)) {
                assertThatThrownBy(() -> s.vault().unlock(wrong)).isInstanceOf(VaultException.class)
                        .satisfies(e -> UiErrors.log("unlock", e));
            }
            // A third-party exception whose message echoes input must be logged without its message.
            UiErrors.log("simulated library failure", new IllegalStateException("bad input: " + PASSWORD,
                    new RuntimeException("nested " + MASTER)));
            UiErrors.message(new IllegalArgumentException("echo " + ANSWER));
            Logger.getLogger("vaultlocal").info("VaultLocal starting");
        } finally {
            for (Handler h : Logger.getLogger("").getHandlers()) {
                h.flush();
                h.close();
            }
        }

        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.list(logs)) {
            for (Path f : files.toList()) {
                all.append(Files.readString(f, StandardCharsets.UTF_8));
            }
        }
        assertThat(all).contains("simulated library failure", "java.lang.IllegalStateException", "WRONG_MASTER_KEY");
        for (String canary : List.of(MASTER, WRONG, PASSWORD, ANSWER, LABEL)) {
            assertThat(all.toString()).as("log contains " + canary).doesNotContain(canary);
        }
    }

    private static Secret sec(String s) {
        return Secret.wrap(s.toCharArray());
    }
}
