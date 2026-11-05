package com.codewitheli.vaultlocal.application.port.out;

import com.codewitheli.vaultlocal.domain.audit.ActivityEvent;
import com.codewitheli.vaultlocal.domain.audit.ActivityType;
import com.codewitheli.vaultlocal.domain.entry.EntryId;
import com.codewitheli.vaultlocal.domain.policy.LockoutChannel;
import com.codewitheli.vaultlocal.domain.policy.LockoutState;
import com.codewitheli.vaultlocal.domain.recovery.SecurityQuestion;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Outbound persistence ports, grouped in one file because each is small. */
public final class Repositories {

    private Repositories() {
    }

    public interface VaultMetaRepository {
        Optional<VaultMeta> find();

        void save(VaultMeta meta);
    }

    public interface KeyWrapRepository {
        Optional<KeyWrap> find(WrapType type);

        /** Inserts or replaces the single row for {@code wrap.type()}. */
        void save(KeyWrap wrap);

        void delete(WrapType type);
    }

    public interface SecurityQuestionRepository {
        List<SecurityQuestion> findAll();

        void replaceAll(List<SecurityQuestion> questions);
    }

    public interface EntryRepository {
        List<EntryRecord> findAll();

        Optional<EntryRecord> find(EntryId id);

        void insert(EntryRecord entry);

        void update(EntryRecord entry);

        void delete(EntryId id);

        void deleteAll();
    }

    public interface PasswordHistoryRepository {
        /** Newest first. */
        List<HistoryRecord> find(EntryId entryId);

        List<HistoryRecord> findAll();

        /** Inserts as seq 1, shifts the others, and drops anything beyond {@code keep}. */
        void push(EntryId entryId, byte[] sealedPassword, Instant replacedAt, int keep);

        void replace(HistoryRecord record);
    }

    public interface LockoutRepository {
        LockoutState find(LockoutChannel channel);

        void save(LockoutState state);
    }

    public interface ActivityLogRepository {
        void append(Instant at, ActivityType type, EntryId entryId);

        /** Newest first. */
        List<ActivityEvent> recent(int limit);

        void prune(int keep);
    }

    public interface SettingsRepository {
        Optional<String> get(String key);

        void put(String key, String value);
    }
}
