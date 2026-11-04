package com.codewitheli.vaultlocal.application;

import com.codewitheli.vaultlocal.adapter.crypto.AesGcmAead;
import com.codewitheli.vaultlocal.adapter.crypto.Argon2idKdf;
import com.codewitheli.vaultlocal.adapter.crypto.SecureRandomSource;
import com.codewitheli.vaultlocal.adapter.persistence.SqliteDatabase;
import com.codewitheli.vaultlocal.adapter.persistence.SqliteRepositories;
import com.codewitheli.vaultlocal.application.keys.KdfProfile;
import com.codewitheli.vaultlocal.application.port.out.ClockPort;
import com.codewitheli.vaultlocal.application.port.out.PasswordStrengthPort;
import com.codewitheli.vaultlocal.domain.recovery.PredefinedQuestion;
import com.codewitheli.vaultlocal.domain.recovery.SecurityQuestion;
import com.codewitheli.vaultlocal.domain.secret.Secret;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** A real vault on disk (SQLite + Argon2id + AES-GCM) with cheap KDF costs and a settable clock. */
final class TestVault implements AutoCloseable {

    static final List<SecurityQuestion> QUESTIONS = List.of(
            SecurityQuestion.predefined(PredefinedQuestion.FIRST_PET),
            SecurityQuestion.predefined(PredefinedQuestion.FIRST_CONCERT),
            SecurityQuestion.custom("What did we name the office plant?"));

    final Path file;
    final MutableClock clock;
    SqliteDatabase db;
    VaultServices services;

    private TestVault(Path file, MutableClock clock) {
        this.file = file;
        this.clock = clock;
        open();
    }

    static TestVault in(Path dir) {
        return new TestVault(dir.resolve("vault.db"), new MutableClock(Instant.parse("2026-11-05T10:00:00Z")));
    }

    private void open() {
        db = SqliteDatabase.open(file);
        SqliteRepositories repos = new SqliteRepositories(db);
        SecureRandomSource random = new SecureRandomSource();
        services = VaultServices.create(new VaultServices.Ports(new Argon2idKdf(), new AesGcmAead(random), random,
                new FakeStrength(), clock, db, repos.vaultMeta(), repos.keyWraps(), repos.securityQuestions(),
                repos.entries(), repos.passwordHistory(), repos.lockouts(), repos.activityLog(), repos.settings()),
                KdfProfile.FOR_TESTS);
    }

    /** Simulates quitting and relaunching the app: new connection, new services, empty session. */
    void restart() {
        services.session().lock();
        db.close();
        open();
    }

    void create(String masterKey, String... answers) {
        try (Secret mk = s(masterKey)) {
            List<Secret> raw = java.util.Arrays.stream(answers).map(TestVault::s).toList();
            try {
                services.vault().createVault(mk, true, QUESTIONS, raw);
            } finally {
                raw.forEach(Secret::close);
            }
        }
    }

    static Secret s(String text) {
        return Secret.wrap(text.toCharArray());
    }

    @Override
    public void close() {
        services.session().lock();
        db.close();
    }

    static final class MutableClock implements ClockPort {
        private Instant now;
        private long nanos;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
            nanos += d.toNanos();
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public long monotonicNanos() {
            return nanos;
        }
    }

    /** Deterministic stand-in for zxcvbn: few distinct characters = weak. */
    static final class FakeStrength implements PasswordStrengthPort {
        @Override
        public StrengthResult estimate(Secret password) {
            long distinct = new String(password.chars()).chars().distinct().count();
            int score = distinct <= 3 ? 0 : password.length() < 10 ? 1 : 3;
            return new StrengthResult(score, "", List.of());
        }
    }
}
