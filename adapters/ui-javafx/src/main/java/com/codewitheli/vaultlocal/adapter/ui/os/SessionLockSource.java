package com.codewitheli.vaultlocal.adapter.ui.os;

/** OS signals that should lock the vault immediately (screen locked, going to sleep). */
public interface SessionLockSource extends AutoCloseable {

    /** {@code onLock} may be called from any thread. */
    void start(Runnable onLock);

    @Override
    void close();

    SessionLockSource NONE = new SessionLockSource() {
        @Override
        public void start(Runnable onLock) {
        }

        @Override
        public void close() {
        }
    };
}
