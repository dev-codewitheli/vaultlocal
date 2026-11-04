package com.codewitheli.vaultlocal.adapter.crypto;

import com.codewitheli.vaultlocal.application.port.out.KdfParams;
import com.codewitheli.vaultlocal.application.port.out.KeyDerivationPort;
import com.codewitheli.vaultlocal.domain.secret.SecretBytes;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/** Argon2id v1.3 (RFC 9106) via Bouncy Castle's lightweight API. Not registered as a JCA provider. */
public final class Argon2idKdf implements KeyDerivationPort {

    @Override
    public SecretBytes derive(SecretBytes input, byte[] salt, KdfParams params) {
        return SecretBytes.wrap(generate(input.bytes(), salt, params, null, null, KEY_BYTES));
    }

    /** Package-private so tests can run the RFC 9106 vector, which also uses secret and AD inputs. */
    static byte[] generate(byte[] password, byte[] salt, KdfParams params, byte[] secret, byte[] ad, int length) {
        Argon2Parameters.Builder builder = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(params.memoryKib())
                .withIterations(params.iterations())
                .withParallelism(params.parallelism())
                .withSalt(salt);
        if (secret != null) {
            builder.withSecret(secret);
        }
        if (ad != null) {
            builder.withAdditional(ad);
        }
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(builder.build());
        byte[] out = new byte[length];
        generator.generateBytes(password, out);
        return out;
    }
}
