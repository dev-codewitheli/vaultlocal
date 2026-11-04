package com.codewitheli.vaultlocal.domain.error;

/**
 * Every error the vault can report. Messages are fixed text: user input, secret lengths and
 * third-party exception messages never become part of an error.
 */
public enum ErrorCode {
    WRONG_MASTER_KEY("The master key is incorrect."),
    WRONG_ANSWERS("One or more answers are incorrect."),
    LOCKED_OUT("Too many failed attempts. Please wait."),
    VAULT_NOT_SET_UP("No vault exists yet."),
    VAULT_ALREADY_EXISTS("A vault already exists."),
    VAULT_LOCKED("The vault is locked."),
    VAULT_CORRUPTED("The vault data could not be decrypted. It may be damaged or tampered with."),
    RECOVERY_DISABLED("Security-question recovery is turned off for this vault."),
    MASTER_KEY_TOO_SHORT("The master key must be at least 12 characters."),
    MASTER_KEY_TOO_WEAK("The master key is too easy to guess. Make it longer or less predictable."),
    MASTER_KEY_MISMATCH("The two master keys do not match."),
    QUESTIONS_INVALID("Choose three different security questions."),
    ANSWER_TOO_SHORT("Each answer must be at least 3 characters."),
    ANSWER_MISMATCH("The answer and its confirmation do not match."),
    ENTRY_INVALID("Platform, account label, username and password are required."),
    ENTRY_NOT_FOUND("The entry no longer exists."),
    URL_INVALID("The website URL is not valid. Use http:// or https://."),
    INVALID_TEXT("The text contains characters that cannot be stored."),
    BACKUP_INVALID("The file is not a VaultLocal backup, or it is damaged."),
    BACKUP_WRONG_KEY("That master key does not open this backup."),
    STORAGE_FAILURE("The vault file could not be read or written.");

    private final String message;

    ErrorCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
