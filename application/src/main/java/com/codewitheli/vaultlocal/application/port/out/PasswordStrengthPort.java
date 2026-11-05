package com.codewitheli.vaultlocal.application.port.out;

import com.codewitheli.vaultlocal.domain.secret.Secret;

import java.util.List;

/** Password strength estimation. Implementations must not retain or log the password. */
public interface PasswordStrengthPort {

    StrengthResult estimate(Secret password);

    /**
     * @param score       0 (very weak) .. 4 (very strong)
     * @param warning     generic advice text, never derived from the password's characters
     * @param suggestions generic advice text
     */
    record StrengthResult(int score, String warning, List<String> suggestions) {

        public StrengthResult {
            if (score < 0 || score > 4) {
                throw new IllegalArgumentException("score must be 0..4");
            }
            warning = warning == null ? "" : warning;
            suggestions = List.copyOf(suggestions == null ? List.of() : suggestions);
        }

        public String label() {
            return switch (score) {
                case 0 -> "Very weak";
                case 1 -> "Weak";
                case 2 -> "Fair";
                case 3 -> "Strong";
                default -> "Very strong";
            };
        }
    }
}
