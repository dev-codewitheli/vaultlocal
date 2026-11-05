package com.codewitheli.vaultlocal.application.port.out;

/**
 * Argon2id parameters, stored next to every key wrap so they can be raised later.
 *
 * @param memoryKib   memory cost in KiB
 * @param iterations  time cost (passes)
 * @param parallelism lanes
 */
public record KdfParams(String algorithm, int version, int memoryKib, int iterations, int parallelism) {

    public static final String ARGON2ID = "argon2id";
    public static final int VERSION_13 = 0x13;

    public KdfParams {
        if (!ARGON2ID.equals(algorithm) || version != VERSION_13) {
            throw new IllegalArgumentException("unsupported KDF");
        }
        if (memoryKib < 8 * parallelism || iterations < 1 || parallelism < 1) {
            throw new IllegalArgumentException("invalid KDF cost");
        }
    }

    public static KdfParams argon2id(int memoryKib, int iterations, int parallelism) {
        return new KdfParams(ARGON2ID, VERSION_13, memoryKib, iterations, parallelism);
    }
}
