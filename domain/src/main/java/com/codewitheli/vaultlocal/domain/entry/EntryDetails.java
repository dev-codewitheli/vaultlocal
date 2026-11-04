package com.codewitheli.vaultlocal.domain.entry;

import com.codewitheli.vaultlocal.domain.error.ErrorCode;
import com.codewitheli.vaultlocal.domain.error.VaultException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * The editable, non-password fields of an entry. These are encrypted at rest but held as
 * {@link String} while the vault is unlocked (DESIGN.md §4.3, "Tier 2").
 *
 * @param websiteUrl optional; {@code null} when absent. Never fetched or opened.
 * @param notes      optional; {@code null} when absent.
 */
public record EntryDetails(Platform platform, String accountLabel, String username,
                           String websiteUrl, String notes) {

    public static final int MAX_LABEL = 100;
    public static final int MAX_USERNAME = 200;
    public static final int MAX_URL = 2048;
    public static final int MAX_NOTES = 10_000;

    public EntryDetails {
        if (platform == null) {
            throw new VaultException(ErrorCode.ENTRY_INVALID);
        }
        accountLabel = required(accountLabel, MAX_LABEL);
        username = required(username, MAX_USERNAME);
        websiteUrl = normalizeUrl(websiteUrl);
        notes = optional(notes, MAX_NOTES);
    }

    private static String required(String value, int max) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty() || v.length() > max) {
            throw new VaultException(ErrorCode.ENTRY_INVALID);
        }
        return v;
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > max) {
            throw new VaultException(ErrorCode.ENTRY_INVALID);
        }
        return value;
    }

    /** Adds {@code https://} when no scheme is given; accepts only http(s) URLs with a host. */
    static String normalizeUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String url = raw.strip();
        if (url.length() > MAX_URL) {
            throw new VaultException(ErrorCode.URL_INVALID);
        }
        if (!url.contains("://")) {
            url = "https://" + url;
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
                throw new VaultException(ErrorCode.URL_INVALID);
            }
            return url;
        } catch (URISyntaxException e) {
            throw new VaultException(ErrorCode.URL_INVALID);
        }
    }
}
