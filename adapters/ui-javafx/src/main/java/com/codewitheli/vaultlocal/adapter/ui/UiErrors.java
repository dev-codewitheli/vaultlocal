package com.codewitheli.vaultlocal.adapter.ui;

import com.codewitheli.vaultlocal.domain.error.VaultException;

/**
 * Turns exceptions into user-facing text and safe log lines. Only our own fixed
 * {@code ErrorCode} messages are ever shown or logged; any other exception is logged by class
 * name and stack frames only, because third-party messages could echo input.
 */
public final class UiErrors {

    private static final System.Logger LOG = System.getLogger("vaultlocal.ui");

    private UiErrors() {
    }

    public static String message(Throwable t) {
        if (t instanceof VaultException ve) {
            return ve.code().message();
        }
        log("Unexpected error", t);
        return "Something went wrong. Details (without any secrets) were written to the log.";
    }

    public static void log(String context, Throwable t) {
        LOG.log(System.Logger.Level.ERROR, context + ": " + describe(t));
    }

    /** Class names, error codes and stack frames only; never {@code getMessage()} of foreign exceptions. */
    public static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable current = t;
        int depth = 0;
        while (current != null && depth++ < 5) {
            if (depth > 1) {
                sb.append(" | caused by ");
            }
            sb.append(current.getClass().getName());
            if (current instanceof VaultException ve) {
                sb.append('[').append(ve.code()).append(']');
            }
            StackTraceElement[] frames = current.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 8); i++) {
                sb.append("\n    at ").append(frames[i]);
            }
            current = current.getCause();
        }
        return sb.toString();
    }
}
