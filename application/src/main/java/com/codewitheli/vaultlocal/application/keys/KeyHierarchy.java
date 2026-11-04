package com.codewitheli.vaultlocal.application.keys;

import com.codewitheli.vaultlocal.application.port.out.AeadPort;
import com.codewitheli.vaultlocal.application.port.out.KdfParams;
import com.codewitheli.vaultlocal.application.port.out.KeyDerivationPort;
import com.codewitheli.vaultlocal.application.port.out.KeyWrap;
import com.codewitheli.vaultlocal.application.port.out.RandomPort;
import com.codewitheli.vaultlocal.application.port.out.WrapType;
import com.codewitheli.vaultlocal.domain.secret.SecretBytes;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/**
 * Envelope encryption (DESIGN.md §4): a random DEK, wrapped by KEKs derived with Argon2id. This is
 * the only class that handles KEKs. A wrong master key or wrong answers simply fail to
 * authenticate the wrap; there is no stored verifier.
 */
public final class KeyHierarchy {

    public static final int DEK_BYTES = 32;
    public static final int SALT_BYTES = 16;

    private final KeyDerivationPort kdf;
    private final AeadPort aead;
    private final RandomPort random;

    public KeyHierarchy(KeyDerivationPort kdf, AeadPort aead, RandomPort random) {
        this.kdf = kdf;
        this.aead = aead;
        this.random = random;
    }

    public SecretBytes newDek() {
        return SecretBytes.wrap(random.strongBytes(DEK_BYTES));
    }

    /** Derives a KEK with a brand-new salt. */
    public Kek deriveNewKek(SecretBytes input, KdfParams params) {
        byte[] salt = random.strongBytes(SALT_BYTES);
        return new Kek(params, salt, kdf.derive(input, salt, params));
    }

    public KeyWrap seal(WrapType type, Kek kek, SecretBytes dek, String vaultId, int dekGeneration, Instant now) {
        byte[] sealed = aead.seal(kek.key(), dek.bytes(), Aad.wrap(type, vaultId, dekGeneration));
        return new KeyWrap(type, kek.params(), kek.salt(), sealed, now);
    }

    /** New salt, new KEK, sealed DEK. */
    public KeyWrap wrapNew(WrapType type, SecretBytes input, KdfParams params, SecretBytes dek,
                           String vaultId, int dekGeneration, Instant now) {
        try (Kek kek = deriveNewKek(input, params)) {
            return seal(type, kek, dek, vaultId, dekGeneration, now);
        }
    }

    /** @return the DEK and KEK, or empty when {@code input} is wrong (or the wrap was tampered with). */
    public Optional<Unwrapped> unwrap(KeyWrap wrap, SecretBytes input, String vaultId, int dekGeneration) {
        Kek kek = new Kek(wrap.params(), wrap.salt(), kdf.derive(input, wrap.salt(), wrap.params()));
        Optional<byte[]> dek = aead.open(kek.key(), wrap.wrappedDek(), Aad.wrap(wrap.type(), vaultId, dekGeneration));
        if (dek.isEmpty()) {
            kek.close();
            return Optional.empty();
        }
        byte[] raw = dek.get();
        if (raw.length != DEK_BYTES) {
            Arrays.fill(raw, (byte) 0);
            kek.close();
            return Optional.empty();
        }
        return Optional.of(new Unwrapped(SecretBytes.wrap(raw), kek));
    }

    /** Verification only: true when {@code input} unwraps the wrap. All key material is zeroed. */
    public boolean verifies(KeyWrap wrap, SecretBytes input, String vaultId, int dekGeneration) {
        Optional<Unwrapped> result = unwrap(wrap, input, vaultId, dekGeneration);
        result.ifPresent(Unwrapped::close);
        return result.isPresent();
    }
}
