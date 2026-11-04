package com.codewitheli.vaultlocal.domain.entry;

import com.codewitheli.vaultlocal.domain.error.ErrorCode;
import com.codewitheli.vaultlocal.domain.error.VaultException;

import java.util.Objects;

/** A built-in platform, or a custom one with a user-chosen name. */
public record Platform(PlatformKind kind, String customName) {

    public static final int MAX_CUSTOM_NAME = 40;
    private static final String CUSTOM_PREFIX = "CUSTOM:";

    public Platform {
        Objects.requireNonNull(kind, "kind");
        if (kind == PlatformKind.CUSTOM) {
            customName = customName == null ? "" : customName.strip();
            if (customName.isEmpty() || customName.length() > MAX_CUSTOM_NAME) {
                throw new VaultException(ErrorCode.ENTRY_INVALID);
            }
        } else {
            customName = null;
        }
    }

    public static Platform of(PlatformKind kind) {
        return new Platform(kind, null);
    }

    public static Platform custom(String name) {
        return new Platform(PlatformKind.CUSTOM, name);
    }

    public String displayName() {
        return kind == PlatformKind.CUSTOM ? customName : kind.displayName();
    }

    /** Stable text form, encrypted before it is stored. */
    public String encode() {
        return kind == PlatformKind.CUSTOM ? CUSTOM_PREFIX + customName : kind.name();
    }

    public static Platform decode(String encoded) {
        if (encoded.startsWith(CUSTOM_PREFIX)) {
            return custom(encoded.substring(CUSTOM_PREFIX.length()));
        }
        return of(PlatformKind.valueOf(encoded));
    }
}
