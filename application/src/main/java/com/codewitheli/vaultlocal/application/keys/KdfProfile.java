package com.codewitheli.vaultlocal.application.keys;

import com.codewitheli.vaultlocal.application.port.out.KdfParams;

/** KDF costs for new wraps (existing wraps keep the params stored with them). DESIGN.md §4.2. */
public record KdfProfile(KdfParams master, KdfParams recovery) {

    /** Master: 128 MiB, t=3, p=4. Recovery: 256 MiB, t=6, p=4. */
    public static final KdfProfile PRODUCTION = new KdfProfile(
            KdfParams.argon2id(128 * 1024, 3, 4),
            KdfParams.argon2id(256 * 1024, 6, 4));

    /** Cheap parameters so the test suite stays fast. Never used by the application. */
    public static final KdfProfile FOR_TESTS = new KdfProfile(
            KdfParams.argon2id(8 * 1024, 1, 1),
            KdfParams.argon2id(8 * 1024, 2, 1));
}
