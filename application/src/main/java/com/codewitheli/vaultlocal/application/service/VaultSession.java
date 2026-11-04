package com.codewitheli.vaultlocal.application.service;

import com.codewitheli.vaultlocal.application.port.out.VaultMeta;
import com.codewitheli.vaultlocal.domain.error.ErrorCode;
import com.codewitheli.vaultlocal.domain.error.VaultException;
import com.codewitheli.vaultlocal.domain.secret.SecretBytes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

/**
 * Holds the unwrapped DEK while the vault is unlocked. {@link #lock()} zeroes it and notifies
 * listeners so the UI can wipe its models. Synchronized so a lock can never zero the key while a
 * background task is using it.
 */
public final class VaultSession {

    private final List<Runnable> lockListeners = new CopyOnWriteArrayList<>();
    private SecretBytes dek;
    private VaultMeta meta;

    /** Takes ownership of {@code newDek}. Replaces (and zeroes) any key already held. */
    public synchronized void open(SecretBytes newDek, VaultMeta newMeta) {
        if (dek != null) {
            dek.close();
        }
        dek = newDek;
        meta = newMeta;
    }

    public synchronized boolean isUnlocked() {
        return dek != null;
    }

    public synchronized <T> T withDek(BiFunction<SecretBytes, VaultMeta, T> work) {
        if (dek == null) {
            throw new VaultException(ErrorCode.VAULT_LOCKED);
        }
        return work.apply(dek, meta);
    }

    synchronized void updateMeta(VaultMeta newMeta) {
        if (dek != null) {
            meta = newMeta;
        }
    }

    public void lock() {
        boolean wasUnlocked;
        synchronized (this) {
            wasUnlocked = dek != null;
            if (dek != null) {
                dek.close();
            }
            dek = null;
            meta = null;
        }
        if (wasUnlocked) {
            lockListeners.forEach(Runnable::run);
        }
    }

    public void addLockListener(Runnable listener) {
        lockListeners.add(listener);
    }
}
