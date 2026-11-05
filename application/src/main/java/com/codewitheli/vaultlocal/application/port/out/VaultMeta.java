package com.codewitheli.vaultlocal.application.port.out;

import java.time.Instant;

/**
 * The singleton vault descriptor.
 *
 * @param masterKeyLength code points; plaintext so the hint works before unlock (DESIGN.md §5.4)
 * @param dekGeneration   increments whenever the DEK is rotated; part of every AAD
 */
public record VaultMeta(String vaultId, int formatVersion, int masterKeyLength,
                        int dekGeneration, Instant createdAt) {

    public static final int FORMAT_VERSION = 1;

    public VaultMeta withMasterKeyLength(int length) {
        return new VaultMeta(vaultId, formatVersion, length, dekGeneration, createdAt);
    }

    public VaultMeta withDekGeneration(int generation) {
        return new VaultMeta(vaultId, formatVersion, masterKeyLength, generation, createdAt);
    }
}
