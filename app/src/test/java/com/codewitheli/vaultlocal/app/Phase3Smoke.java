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
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.GeneratorOptions;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.ImportMode;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.RecoveryTicket;
import com.codewitheli.vaultlocal.application.port.out.ClockPort;
import com.codewitheli.vaultlocal.domain.entry.Entry;
import com.codewitheli.vaultlocal.domain.entry.EntryDetails;
import com.codewitheli.vaultlocal.domain.entry.Platform;
import com.codewitheli.vaultlocal.domain.entry.PlatformKind;
import com.codewitheli.vaultlocal.domain.error.LockedOutException;
import com.codewitheli.vaultlocal.domain.error.WrongSecretException;
import com.codewitheli.vaultlocal.domain.policy.LockoutChannel;
import com.codewitheli.vaultlocal.domain.recovery.PredefinedQuestion;
import com.codewitheli.vaultlocal.domain.recovery.SecurityQuestion;
import com.codewitheli.vaultlocal.domain.secret.Secret;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Phase 3 evidence driver (not part of the product). Runs the real use cases with the production
 * adapters and PRODUCTION KDF costs in a standalone JVM, so the OS can be asked what that process
 * does on the network, and the files it writes can be inspected with the sqlite3 CLI.
 *
 * <pre>
 *   flows     &lt;home&gt;   setup, CRUD, history, reveal, copy, report, backup export+import, change key
 *   lockout-a &lt;home&gt;   4 wrong unlocks (real delays), then the process exits
 *   lockout-b &lt;home&gt;   new process: lockout still running? right key refused, then accepted
 *   recover   &lt;home&gt;   forgot-master-key flow; prints evidence about the old wrap
 * </pre>
 */
public final class Phase3Smoke {

    static final String MASTER = "Phase3-Master-Key-Canary-71";
    static final String NEW_MASTER = "Phase3-New-Master-Key-Canary-72";
    static final String[] ANSWERS = {"CanaryPetName", "CanaryConcert", "CanaryFirstCar"};
    static final String[] PASSWORDS = {"CanaryPw-Gmail-1111", "CanaryPw-Bank-2222", "CanaryPw-Fb-3333"};
    static final String LABEL = "CanaryLabel-Personal";
    static final String USER = "canary.user@example.com";
    static final String NOTES = "CanaryNotes-recovery-codes";

    public static void main(String[] args) throws Exception {
        Path home = AppPaths.ensure(Path.of(args[1]));
        Logging.configure(AppPaths.ensure(home.resolve("logs")));
        try (SqliteDatabase db = SqliteDatabase.open(home.resolve("vault.db"))) {
            VaultServices s = services(db);
            switch (args[0]) {
                case "flows" -> flows(s, home);
                case "lockout-a" -> lockoutA(s);
                case "lockout-b" -> lockoutB(s);
                case "recover" -> recover(s);
                default -> throw new IllegalArgumentException("unknown mode");
            }
            s.session().lock();
        }
        long hold = Long.getLong("smoke.holdMillis", 0);
        if (hold > 0) {
            Thread.sleep(hold);
        }
    }

    private static void flows(VaultServices s, Path home) throws Exception {
        long t = System.nanoTime();
        try (Secret mk = sec(MASTER); Secret a = sec(ANSWERS[0]); Secret b = sec(ANSWERS[1]); Secret c = sec(ANSWERS[2])) {
            s.vault().createVault(mk, true, List.of(
                    SecurityQuestion.predefined(PredefinedQuestion.FIRST_PET),
                    SecurityQuestion.predefined(PredefinedQuestion.FIRST_CONCERT),
                    SecurityQuestion.predefined(PredefinedQuestion.FIRST_CAR)), List.of(a, b, c));
        }
        say("setup (production KDF, both wraps) ms=" + (System.nanoTime() - t) / 1_000_000);
        Entry gmail = add(s, PlatformKind.GMAIL, LABEL, PASSWORDS[0], NOTES);
        add(s, PlatformKind.BANK, "CanaryLabel-Savings", PASSWORDS[1], null);
        add(s, PlatformKind.FACEBOOK, "CanaryLabel-Family", PASSWORDS[2], null);
        try (Secret pw = s.generator().generatePassword(GeneratorOptions.defaults())) {
            s.entries().updateEntry(gmail.id(), gmail.details(), pw, true);
        }
        say("entries=" + s.entries().listEntries().size() + " history(gmail)=" + s.entries().passwordHistory(gmail.id()).size());
        try (Secret mk = sec(MASTER)) {
            t = System.nanoTime();
            s.entries().revealPassword(gmail.id(), mk).close();
            say("reveal verified master key ms=" + (System.nanoTime() - t) / 1_000_000);
            s.entries().passwordForCopy(gmail.id(), mk).close();
            s.entries().revealHistoricPassword(gmail.id(), 1, mk).close();
            byte[] backup = s.backups().exportBackup(mk);
            Files.write(home.resolve("export.vlbackup"), backup);
            say("backup bytes=" + backup.length + " imported=" + s.backups().importBackup(backup, mk, ImportMode.MERGE));
        }
        say("report=" + s.reports().securityReport(90).weak().size() + " weak");
        try (Secret wrong = sec("Phase3-wrong-key-Canary-00")) {
            s.vault().verifyMasterKey(wrong);
        } catch (WrongSecretException e) {
            UiErrors.log("expected wrong key", e);
            say("wrong key rejected, lockout=" + e.lockout().toSeconds() + "s");
        }
        Thread.sleep(1100);
        try (Secret mk = sec(MASTER); Secret next = sec(NEW_MASTER)) {
            s.vault().changeMasterKey(mk, next);
        }
        try (Secret back = sec(NEW_MASTER); Secret orig = sec(MASTER)) {
            s.vault().changeMasterKey(back, orig);
        }
        say("change master key round trip ok");
    }

    private static void lockoutA(VaultServices s) throws Exception {
        for (int i = 1; i <= 4; i++) {
            try (Secret wrong = sec("Phase3-wrong-key-Canary-0" + i)) {
                s.vault().unlock(wrong);
            } catch (WrongSecretException e) {
                say("failure " + i + " -> lockout " + e.lockout().toSeconds() + "s");
                if (i < 4) {
                    Thread.sleep(e.lockout().toMillis() + 50);
                }
            }
        }
        say("process exits now; MASTER lockout remaining=" + s.vault().lockoutRemaining(LockoutChannel.MASTER).toMillis() + "ms");
    }

    private static void lockoutB(VaultServices s) throws Exception {
        say("new process; MASTER lockout remaining=" + s.vault().lockoutRemaining(LockoutChannel.MASTER).toMillis() + "ms");
        try (Secret right = sec(MASTER)) {
            s.vault().unlock(right);
            say("UNEXPECTED: unlocked during lockout");
        } catch (LockedOutException e) {
            say("correct key refused while locked out, remaining=" + e.remaining().toMillis() + "ms");
            Thread.sleep(e.remaining().toMillis() + 100);
        }
        try (Secret right = sec(MASTER)) {
            s.vault().unlock(right);
            say("after the delay: unlocked=" + s.vault().isUnlocked());
        }
    }

    private static void recover(VaultServices s) throws Exception {
        try (Secret a = sec("  canarypetname "); Secret b = sec("CANARYCONCERT"); Secret c = sec("canaryfirstcar");
             RecoveryTicket ticket = s.vault().verifyAnswers(List.of(a, b, c));
             Secret next = sec(NEW_MASTER)) {
            s.vault().completeRecovery(ticket, next);
        }
        say("recovered with normalized answers; new key length=" + s.vault().masterKeyLength());
        s.session().lock();
        try (Secret old = sec(MASTER)) {
            s.vault().unlock(old);
            say("UNEXPECTED: old master key still works");
        } catch (WrongSecretException e) {
            say("old master key rejected");
        }
        Thread.sleep(1100);
        try (Secret next = sec(NEW_MASTER)) {
            s.vault().unlock(next);
            try (Secret pw = s.entries().revealPassword(s.entries().listEntries().stream()
                    .filter(e -> e.accountLabel().equals("CanaryLabel-Savings")).findFirst().orElseThrow().id(), next)) {
                say("entry readable with new key after DEK rotation: " + (pw.length() == PASSWORDS[1].length()));
            }
        }
    }

    private static Entry add(VaultServices s, PlatformKind kind, String label, String password, String notes) {
        try (Secret pw = sec(password)) {
            return s.entries().addEntry(new EntryDetails(Platform.of(kind), label, USER,
                    "https://canary-site.example", notes), pw, false);
        }
    }

    private static VaultServices services(SqliteDatabase db) {
        SqliteRepositories repos = new SqliteRepositories(db);
        SecureRandomSource random = new SecureRandomSource();
        return VaultServices.create(new VaultServices.Ports(new Argon2idKdf(), new AesGcmAead(random), random,
                new LocalStrengthEstimator(), ClockPort.SYSTEM, db, repos.vaultMeta(), repos.keyWraps(),
                repos.securityQuestions(), repos.entries(), repos.passwordHistory(), repos.lockouts(),
                repos.activityLog(), repos.settings()), KdfProfile.PRODUCTION);
    }

    private static Secret sec(String s) {
        return Secret.wrap(s.toCharArray());
    }

    private static void say(String line) {
        System.out.println("SMOKE " + line);
    }
}
