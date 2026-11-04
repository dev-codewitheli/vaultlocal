package com.codewitheli.vaultlocal.domain.audit;

/** What the local activity log records. None of these carry secret data. */
public enum ActivityType {
    VAULT_CREATED("Vault created"),
    UNLOCK_OK("Unlocked"),
    UNLOCK_FAILED("Unlock failed: wrong master key"),
    VERIFY_FAILED("Master key prompt failed"),
    LOCKED("Locked"),
    AUTO_LOCKED("Locked automatically"),
    REVEAL("Password revealed"),
    COPY("Password copied"),
    HISTORY_REVEAL("Old password revealed"),
    RECOVERY_OK("Recovered with security questions"),
    RECOVERY_FAILED("Recovery failed: wrong answers"),
    MASTER_KEY_CHANGED("Master key changed"),
    QUESTIONS_CHANGED("Security questions changed"),
    RECOVERY_TURNED_OFF("Security-question recovery turned off"),
    ENTRY_CREATED("Entry added"),
    ENTRY_UPDATED("Entry edited"),
    ENTRY_DELETED("Entry deleted"),
    BACKUP_EXPORTED("Backup exported"),
    BACKUP_IMPORTED("Backup imported");

    private final String description;

    ActivityType(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
