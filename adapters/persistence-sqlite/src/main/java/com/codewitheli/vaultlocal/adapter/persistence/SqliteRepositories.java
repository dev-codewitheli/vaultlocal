package com.codewitheli.vaultlocal.adapter.persistence;

import com.codewitheli.vaultlocal.application.port.out.EntryRecord;
import com.codewitheli.vaultlocal.application.port.out.HistoryRecord;
import com.codewitheli.vaultlocal.application.port.out.KdfParams;
import com.codewitheli.vaultlocal.application.port.out.KeyWrap;
import com.codewitheli.vaultlocal.application.port.out.Repositories.ActivityLogRepository;
import com.codewitheli.vaultlocal.application.port.out.Repositories.EntryRepository;
import com.codewitheli.vaultlocal.application.port.out.Repositories.KeyWrapRepository;
import com.codewitheli.vaultlocal.application.port.out.Repositories.LockoutRepository;
import com.codewitheli.vaultlocal.application.port.out.Repositories.PasswordHistoryRepository;
import com.codewitheli.vaultlocal.application.port.out.Repositories.SecurityQuestionRepository;
import com.codewitheli.vaultlocal.application.port.out.Repositories.SettingsRepository;
import com.codewitheli.vaultlocal.application.port.out.Repositories.VaultMetaRepository;
import com.codewitheli.vaultlocal.application.port.out.VaultMeta;
import com.codewitheli.vaultlocal.application.port.out.WrapType;
import com.codewitheli.vaultlocal.domain.audit.ActivityEvent;
import com.codewitheli.vaultlocal.domain.audit.ActivityType;
import com.codewitheli.vaultlocal.domain.entry.EntryId;
import com.codewitheli.vaultlocal.domain.policy.LockoutChannel;
import com.codewitheli.vaultlocal.domain.policy.LockoutState;
import com.codewitheli.vaultlocal.domain.recovery.SecurityQuestion;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** JDBC implementations of every repository port. Values are always bound parameters. */
public final class SqliteRepositories {

    private final SqliteDatabase db;

    public SqliteRepositories(SqliteDatabase db) {
        this.db = db;
    }

    public VaultMetaRepository vaultMeta() {
        return new VaultMetaRepository() {
            @Override
            public Optional<VaultMeta> find() {
                return db.with(c -> {
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT vault_id, format_version, master_key_length, dek_generation, created_at FROM vault_meta WHERE id = 1");
                         ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return Optional.empty();
                        }
                        return Optional.of(new VaultMeta(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                                Instant.ofEpochMilli(rs.getLong(5))));
                    }
                });
            }

            @Override
            public void save(VaultMeta m) {
                update("INSERT INTO vault_meta (id, vault_id, format_version, master_key_length, dek_generation, created_at) "
                        + "VALUES (1, ?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET vault_id = excluded.vault_id, "
                        + "format_version = excluded.format_version, master_key_length = excluded.master_key_length, "
                        + "dek_generation = excluded.dek_generation, created_at = excluded.created_at",
                        m.vaultId(), m.formatVersion(), m.masterKeyLength(), m.dekGeneration(), m.createdAt().toEpochMilli());
            }
        };
    }

    public KeyWrapRepository keyWraps() {
        return new KeyWrapRepository() {
            @Override
            public Optional<KeyWrap> find(WrapType type) {
                return db.with(c -> {
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT kdf_algorithm, kdf_version, kdf_memory_kib, kdf_iterations, kdf_parallelism, salt, "
                                    + "wrapped_dek, updated_at FROM key_wrap WHERE wrap_type = ?")) {
                        ps.setString(1, type.name());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                return Optional.empty();
                            }
                            KdfParams params = new KdfParams(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5));
                            return Optional.of(new KeyWrap(type, params, rs.getBytes(6), rs.getBytes(7),
                                    Instant.ofEpochMilli(rs.getLong(8))));
                        }
                    }
                });
            }

            @Override
            public void save(KeyWrap w) {
                KdfParams p = w.params();
                update("INSERT INTO key_wrap (wrap_type, kdf_algorithm, kdf_version, kdf_memory_kib, kdf_iterations, "
                        + "kdf_parallelism, salt, wrapped_dek, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(wrap_type) DO UPDATE SET kdf_algorithm = excluded.kdf_algorithm, "
                        + "kdf_version = excluded.kdf_version, kdf_memory_kib = excluded.kdf_memory_kib, "
                        + "kdf_iterations = excluded.kdf_iterations, kdf_parallelism = excluded.kdf_parallelism, "
                        + "salt = excluded.salt, wrapped_dek = excluded.wrapped_dek, updated_at = excluded.updated_at",
                        w.type().name(), p.algorithm(), p.version(), p.memoryKib(), p.iterations(), p.parallelism(),
                        w.salt(), w.wrappedDek(), w.updatedAt().toEpochMilli());
            }

            @Override
            public void delete(WrapType type) {
                update("DELETE FROM key_wrap WHERE wrap_type = ?", type.name());
            }
        };
    }

    public SecurityQuestionRepository securityQuestions() {
        return new SecurityQuestionRepository() {
            @Override
            public List<SecurityQuestion> findAll() {
                return db.with(c -> {
                    List<SecurityQuestion> out = new ArrayList<>();
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT question_key, custom_text FROM security_question ORDER BY slot");
                         ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(new SecurityQuestion(rs.getString(1), rs.getString(2)));
                        }
                    }
                    return out;
                });
            }

            @Override
            public void replaceAll(List<SecurityQuestion> questions) {
                db.run(() -> {
                    update("DELETE FROM security_question");
                    for (int i = 0; i < questions.size(); i++) {
                        SecurityQuestion q = questions.get(i);
                        update("INSERT INTO security_question (slot, question_key, custom_text) VALUES (?, ?, ?)",
                                i + 1, q.questionKey(), q.customText());
                    }
                });
            }
        };
    }

    public EntryRepository entries() {
        return new EntryRepository() {
            private static final String COLUMNS = "id, platform, account_label, username, password, website_url, notes, "
                    + "favorite, created_at, updated_at, password_changed_at";

            @Override
            public List<EntryRecord> findAll() {
                return db.with(c -> {
                    List<EntryRecord> out = new ArrayList<>();
                    try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM entry");
                         ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(map(rs));
                        }
                    }
                    return out;
                });
            }

            @Override
            public Optional<EntryRecord> find(EntryId id) {
                return db.with(c -> {
                    try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM entry WHERE id = ?")) {
                        ps.setString(1, id.toString());
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                        }
                    }
                });
            }

            @Override
            public void insert(EntryRecord e) {
                SqliteRepositories.this.update("INSERT INTO entry (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        e.id().toString(), e.platform(), e.accountLabel(), e.username(), e.password(),
                        e.websiteUrl(), e.notes(), e.favorite() ? 1 : 0, e.createdAt().toEpochMilli(),
                        e.updatedAt().toEpochMilli(), e.passwordChangedAt().toEpochMilli());
            }

            @Override
            public void update(EntryRecord e) {
                SqliteRepositories.this.update("UPDATE entry SET platform = ?, account_label = ?, username = ?, password = ?, "
                                + "website_url = ?, notes = ?, favorite = ?, created_at = ?, updated_at = ?, "
                                + "password_changed_at = ? WHERE id = ?",
                        e.platform(), e.accountLabel(), e.username(), e.password(), e.websiteUrl(), e.notes(),
                        e.favorite() ? 1 : 0, e.createdAt().toEpochMilli(), e.updatedAt().toEpochMilli(),
                        e.passwordChangedAt().toEpochMilli(), e.id().toString());
            }

            @Override
            public void delete(EntryId id) {
                SqliteRepositories.this.update("DELETE FROM entry WHERE id = ?", id.toString());
            }

            @Override
            public void deleteAll() {
                SqliteRepositories.this.update("DELETE FROM entry");
            }

            private EntryRecord map(ResultSet rs) throws SQLException {
                return new EntryRecord(EntryId.parse(rs.getString(1)), rs.getBytes(2), rs.getBytes(3), rs.getBytes(4),
                        rs.getBytes(5), rs.getBytes(6), rs.getBytes(7), rs.getInt(8) != 0,
                        Instant.ofEpochMilli(rs.getLong(9)), Instant.ofEpochMilli(rs.getLong(10)),
                        Instant.ofEpochMilli(rs.getLong(11)));
            }
        };
    }

    public PasswordHistoryRepository passwordHistory() {
        return new PasswordHistoryRepository() {
            @Override
            public List<HistoryRecord> find(EntryId entryId) {
                return query("SELECT entry_id, seq, password, replaced_at FROM password_history WHERE entry_id = ? ORDER BY seq",
                        entryId.toString());
            }

            @Override
            public List<HistoryRecord> findAll() {
                return query("SELECT entry_id, seq, password, replaced_at FROM password_history ORDER BY entry_id, seq");
            }

            @Override
            public void push(EntryId entryId, byte[] sealedPassword, Instant replacedAt, int keep) {
                String id = entryId.toString();
                db.run(() -> {
                    // Two steps so the (entry_id, seq) primary key never collides mid-update.
                    update("UPDATE password_history SET seq = -(seq + 1) WHERE entry_id = ?", id);
                    update("UPDATE password_history SET seq = -seq WHERE entry_id = ?", id);
                    update("INSERT INTO password_history (entry_id, seq, password, replaced_at) VALUES (?, 1, ?, ?)",
                            id, sealedPassword, replacedAt.toEpochMilli());
                    update("DELETE FROM password_history WHERE entry_id = ? AND seq > ?", id, keep);
                });
            }

            @Override
            public void replace(HistoryRecord r) {
                update("UPDATE password_history SET password = ? WHERE entry_id = ? AND seq = ?",
                        r.password(), r.entryId().toString(), r.seq());
            }

            private List<HistoryRecord> query(String sql, Object... args) {
                return db.with(c -> {
                    List<HistoryRecord> out = new ArrayList<>();
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        bind(ps, args);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                out.add(new HistoryRecord(EntryId.parse(rs.getString(1)), rs.getInt(2), rs.getBytes(3),
                                        Instant.ofEpochMilli(rs.getLong(4))));
                            }
                        }
                    }
                    return out;
                });
            }
        };
    }

    public LockoutRepository lockouts() {
        return new LockoutRepository() {
            @Override
            public LockoutState find(LockoutChannel channel) {
                return db.with(c -> {
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT consecutive_failures, last_failure_at, locked_until FROM lockout_state WHERE channel = ?")) {
                        ps.setString(1, channel.name());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                return LockoutState.initial(channel);
                            }
                            return new LockoutState(channel, rs.getInt(1), instantOrNull(rs, 2), instantOrNull(rs, 3));
                        }
                    }
                });
            }

            @Override
            public void save(LockoutState s) {
                update("INSERT INTO lockout_state (channel, consecutive_failures, last_failure_at, locked_until) "
                                + "VALUES (?, ?, ?, ?) ON CONFLICT(channel) DO UPDATE SET "
                                + "consecutive_failures = excluded.consecutive_failures, "
                                + "last_failure_at = excluded.last_failure_at, locked_until = excluded.locked_until",
                        s.channel().name(), s.consecutiveFailures(), millisOrNull(s.lastFailureAt()),
                        millisOrNull(s.lockedUntil()));
            }
        };
    }

    public ActivityLogRepository activityLog() {
        return new ActivityLogRepository() {
            @Override
            public void append(Instant at, ActivityType type, EntryId entryId) {
                update("INSERT INTO activity_log (occurred_at, event_type, entry_id) VALUES (?, ?, ?)",
                        at.toEpochMilli(), type.name(), entryId == null ? null : entryId.toString());
            }

            @Override
            public List<ActivityEvent> recent(int limit) {
                return db.with(c -> {
                    List<ActivityEvent> out = new ArrayList<>();
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT id, occurred_at, event_type, entry_id FROM activity_log ORDER BY id DESC LIMIT ?")) {
                        ps.setInt(1, limit);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                ActivityType type;
                                try {
                                    type = ActivityType.valueOf(rs.getString(3));
                                } catch (IllegalArgumentException unknown) {
                                    continue;
                                }
                                String entry = rs.getString(4);
                                out.add(new ActivityEvent(rs.getLong(1), Instant.ofEpochMilli(rs.getLong(2)), type,
                                        entry == null ? null : EntryId.parse(entry)));
                            }
                        }
                    }
                    return out;
                });
            }

            @Override
            public void prune(int keep) {
                update("DELETE FROM activity_log WHERE id NOT IN (SELECT id FROM activity_log ORDER BY id DESC LIMIT ?)", keep);
            }
        };
    }

    public SettingsRepository settings() {
        return new SettingsRepository() {
            @Override
            public Optional<String> get(String key) {
                return db.with(c -> {
                    try (PreparedStatement ps = c.prepareStatement("SELECT value FROM settings WHERE key = ?")) {
                        ps.setString(1, key);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
                        }
                    }
                });
            }

            @Override
            public void put(String key, String value) {
                update("INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                        key, value);
            }
        };
    }

    private void update(String sql, Object... args) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args);
                return ps.executeUpdate();
            }
        });
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            int idx = i + 1;
            switch (a) {
                case null -> ps.setNull(idx, Types.NULL);
                case byte[] b -> ps.setBytes(idx, b);
                case String s -> ps.setString(idx, s);
                case Integer n -> ps.setInt(idx, n);
                case Long n -> ps.setLong(idx, n);
                default -> throw new IllegalArgumentException("unsupported parameter type");
            }
        }
    }

    private static Instant instantOrNull(ResultSet rs, int column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(v);
    }

    private static Long millisOrNull(Instant i) {
        return i == null ? null : i.toEpochMilli();
    }
}
