package com.codewitheli.vaultlocal.domain.entry;

/** Built-in platforms, plus {@link #CUSTOM} for a user-named one. */
public enum PlatformKind {
    FACEBOOK("Facebook"),
    TIKTOK("TikTok"),
    GMAIL("Gmail"),
    INSTAGRAM("Instagram"),
    X("X"),
    BANK("Bank"),
    OTHER("Other"),
    CUSTOM("Custom…");

    private final String displayName;

    PlatformKind(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
