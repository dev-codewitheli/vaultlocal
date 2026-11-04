package com.codewitheli.vaultlocal.application.service;

import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.GeneratePasswordUseCase;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.GeneratorOptions;
import com.codewitheli.vaultlocal.application.port.out.PasswordStrengthPort;
import com.codewitheli.vaultlocal.application.port.out.PasswordStrengthPort.StrengthResult;
import com.codewitheli.vaultlocal.application.port.out.RandomPort;
import com.codewitheli.vaultlocal.domain.secret.Secret;

import java.util.ArrayList;
import java.util.List;

/**
 * Random passwords and master keys from {@link RandomPort} (SecureRandom; its {@code nextInt}
 * is unbiased). Output goes straight into a {@code char[]}.
 */
public final class GeneratorService implements GeneratePasswordUseCase {

    static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    static final String DIGITS = "0123456789";
    static final String SYMBOLS = "!@#$%^&*()-_=+[]{};:,.?/~";
    static final String AMBIGUOUS = "0Oo1lI|";

    /** 56 characters: A–Z without I, O; a–z without l, o; 2–9. 24 of them ≈ 139 bits. */
    static final String MASTER_KEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    public static final int MASTER_KEY_LENGTH = 24;

    private final RandomPort random;
    private final PasswordStrengthPort strength;

    public GeneratorService(RandomPort random, PasswordStrengthPort strength) {
        this.random = random;
        this.strength = strength;
    }

    @Override
    public Secret generatePassword(GeneratorOptions o) {
        int length = Math.max(GeneratorOptions.MIN_LENGTH, Math.min(GeneratorOptions.MAX_LENGTH, o.length()));
        List<String> classes = new ArrayList<>();
        if (o.upper()) {
            classes.add(filter(UPPER, o.excludeAmbiguous()));
        }
        if (o.lower()) {
            classes.add(filter(LOWER, o.excludeAmbiguous()));
        }
        if (o.digits()) {
            classes.add(filter(DIGITS, o.excludeAmbiguous()));
        }
        if (o.symbols()) {
            classes.add(filter(SYMBOLS, o.excludeAmbiguous()));
        }
        if (classes.isEmpty()) {
            classes.add(filter(LOWER, o.excludeAmbiguous()));
        }
        String all = String.join("", classes);
        char[] out = new char[length];
        // One character from every selected class, the rest from the union, then shuffle.
        int i = 0;
        for (String cls : classes) {
            out[i++] = cls.charAt(random.nextInt(cls.length()));
        }
        while (i < length) {
            out[i++] = all.charAt(random.nextInt(all.length()));
        }
        for (int j = length - 1; j > 0; j--) {
            int k = random.nextInt(j + 1);
            char t = out[j];
            out[j] = out[k];
            out[k] = t;
        }
        return Secret.wrap(out);
    }

    @Override
    public Secret generateMasterKey() {
        char[] out = new char[MASTER_KEY_LENGTH];
        for (int i = 0; i < out.length; i++) {
            out[i] = MASTER_KEY_ALPHABET.charAt(random.nextInt(MASTER_KEY_ALPHABET.length()));
        }
        return Secret.wrap(out);
    }

    @Override
    public StrengthResult strength(Secret password) {
        return strength.estimate(password);
    }

    private static String filter(String chars, boolean excludeAmbiguous) {
        if (!excludeAmbiguous) {
            return chars;
        }
        StringBuilder sb = new StringBuilder();
        for (char c : chars.toCharArray()) {
            if (AMBIGUOUS.indexOf(c) < 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
