package com.codewitheli.vaultlocal.application.port.out;

import java.util.function.Supplier;

/** Runs a block of repository calls in one database transaction. */
public interface UnitOfWork {

    <T> T inTransaction(Supplier<T> work);

    default void run(Runnable work) {
        inTransaction(() -> {
            work.run();
            return null;
        });
    }

    /** Rebuilds the database file so that no freed page keeps old content. */
    void compact();
}
