package com.codewitheli.vaultlocal.application.service;

import com.codewitheli.vaultlocal.application.keys.EntryCipher;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.SecurityReport;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.SecurityReportUseCase;
import com.codewitheli.vaultlocal.application.port.out.ClockPort;
import com.codewitheli.vaultlocal.application.port.out.EntryRecord;
import com.codewitheli.vaultlocal.application.port.out.PasswordStrengthPort;
import com.codewitheli.vaultlocal.application.port.out.Repositories.EntryRepository;
import com.codewitheli.vaultlocal.domain.entry.Entry;
import com.codewitheli.vaultlocal.domain.entry.EntryId;
import com.codewitheli.vaultlocal.domain.policy.StalenessPolicy;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import com.codewitheli.vaultlocal.domain.secret.SecretBytes;
import com.codewitheli.vaultlocal.domain.secret.SecretCodec;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Weak / reused / stale analysis. Passwords are decrypted one at a time; reuse is detected with
 * SHA-256 digests that live only in this method's memory. Nothing here is persisted.
 */
public final class ReportService implements SecurityReportUseCase {

    public static final int WEAK_MAX_SCORE = 1;

    private final EntryCipher cipher;
    private final VaultSession session;
    private final EntryRepository entries;
    private final PasswordStrengthPort strength;
    private final ClockPort clock;

    public ReportService(EntryCipher cipher, VaultSession session, EntryRepository entries,
                         PasswordStrengthPort strength, ClockPort clock) {
        this.cipher = cipher;
        this.session = session;
        this.entries = entries;
        this.strength = strength;
        this.clock = clock;
    }

    @Override
    public SecurityReport securityReport(int staleDays) {
        Instant now = clock.now();
        return session.withDek((dek, meta) -> {
            List<Entry> weak = new ArrayList<>();
            List<Entry> stale = new ArrayList<>();
            Map<String, List<Entry>> byDigest = new LinkedHashMap<>();
            for (EntryRecord r : entries.findAll()) {
                Entry e = cipher.decrypt(r, dek, meta.dekGeneration());
                try (Secret pw = cipher.openPassword(r, dek, meta.dekGeneration())) {
                    if (strength.estimate(pw).score() <= WEAK_MAX_SCORE) {
                        weak.add(e);
                    }
                    byDigest.computeIfAbsent(digest(pw), k -> new ArrayList<>()).add(e);
                }
                if (StalenessPolicy.isStale(r.passwordChangedAt(), now, staleDays)) {
                    stale.add(e);
                }
            }
            Comparator<Entry> byLabel = Comparator.comparing(Entry::accountLabel, String.CASE_INSENSITIVE_ORDER);
            List<List<Entry>> reused = byDigest.values().stream().filter(g -> g.size() > 1)
                    .map(g -> g.stream().sorted(byLabel).toList()).toList();
            byDigest.clear();
            weak.sort(byLabel);
            stale.sort(byLabel);
            return new SecurityReport(List.copyOf(weak), reused, List.copyOf(stale));
        });
    }

    @Override
    public Map<EntryId, Integer> strengthScores() {
        return session.withDek((dek, meta) -> {
            Map<EntryId, Integer> out = new HashMap<>();
            for (EntryRecord r : entries.findAll()) {
                try (Secret pw = cipher.openPassword(r, dek, meta.dekGeneration())) {
                    out.put(r.id(), strength.estimate(pw).score());
                }
            }
            return out;
        });
    }

    private static String digest(Secret pw) {
        try (SecretBytes utf8 = SecretCodec.utf8(pw)) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(utf8.bytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
