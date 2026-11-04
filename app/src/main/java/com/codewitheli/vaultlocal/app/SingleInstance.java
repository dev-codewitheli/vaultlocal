package com.codewitheli.vaultlocal.app;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** One running VaultLocal per vault, enforced with an OS file lock on {@code vault.lock}. */
public final class SingleInstance implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private SingleInstance(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static Optional<SingleInstance> acquire(Path lockFile) throws IOException {
        FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (java.nio.channels.OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            channel.close();
            return Optional.empty();
        }
        return Optional.of(new SingleInstance(channel, lock));
    }

    @Override
    public void close() {
        try {
            lock.release();
            channel.close();
        } catch (IOException ignored) {
            // the OS releases the lock when the process exits anyway
        }
    }
}
