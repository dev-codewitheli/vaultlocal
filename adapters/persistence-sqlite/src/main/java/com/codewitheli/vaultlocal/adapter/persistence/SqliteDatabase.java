package com.codewitheli.vaultlocal.adapter.persistence;

import com.codewitheli.vaultlocal.application.port.out.UnitOfWork;
import com.codewitheli.vaultlocal.domain.error.ErrorCode;
import com.codewitheli.vaultlocal.domain.error.VaultException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The single SQLite connection, its pragmas, the schema migrations, and transactions.
 * All access is serialized through one lock; transactions nest (only the outermost commits).
 */
public final class SqliteDatabase implements UnitOfWork, AutoCloseable {

    /** Versioned scripts in {@code db/migration}; index + 1 = schema version. */
    private static final List<String> MIGRATIONS = List.of("V1__init.sql");

    private final Connection connection;
    private final ReentrantLock lock = new ReentrantLock();
    private int depth;

    private SqliteDatabase(Connection connection) {
        this.connection = connection;
    }

    public static SqliteDatabase open(Path file) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode = DELETE");
                s.execute("PRAGMA secure_delete = ON");
                s.execute("PRAGMA temp_store = MEMORY");
                s.execute("PRAGMA foreign_keys = ON");
                s.execute("PRAGMA synchronous = FULL");
            }
            SqliteDatabase db = new SqliteDatabase(c);
            db.migrate();
            return db;
        } catch (SQLException e) {
            throw new VaultException(ErrorCode.STORAGE_FAILURE, e);
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    public <T> T with(SqlWork<T> work) {
        lock.lock();
        try {
            return work.apply(connection);
        } catch (SQLException e) {
            throw new VaultException(ErrorCode.STORAGE_FAILURE, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        lock.lock();
        try {
            if (depth == 0) {
                connection.setAutoCommit(false);
            }
            depth++;
            boolean ok = false;
            try {
                T result = work.get();
                ok = true;
                return result;
            } finally {
                depth--;
                if (depth == 0) {
                    if (ok) {
                        connection.commit();
                    } else {
                        connection.rollback();
                    }
                    connection.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            throw new VaultException(ErrorCode.STORAGE_FAILURE, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void compact() {
        with(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("VACUUM");
            }
            return null;
        });
    }

    public int schemaVersion() {
        return with(c -> {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("PRAGMA user_version")) {
                return rs.getInt(1);
            }
        });
    }

    public String pragma(String name) {
        return with(c -> {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("PRAGMA " + name)) {
                return rs.getString(1);
            }
        });
    }

    private void migrate() throws SQLException {
        int current = schemaVersion();
        for (int version = current + 1; version <= MIGRATIONS.size(); version++) {
            String script = load(MIGRATIONS.get(version - 1));
            int target = version;
            inTransaction(() -> with(c -> {
                try (Statement s = c.createStatement()) {
                    for (String sql : script.split(";\\s*(\\r?\\n|$)")) {
                        String stmt = stripComments(sql);
                        if (!stmt.isBlank()) {
                            s.execute(stmt);
                        }
                    }
                    s.execute("PRAGMA user_version = " + target);
                }
                return null;
            }));
        }
    }

    private static String stripComments(String sql) {
        StringBuilder out = new StringBuilder();
        for (String line : sql.split("\\r?\\n")) {
            int dash = line.indexOf("--");
            out.append(dash >= 0 ? line.substring(0, dash) : line).append('\n');
        }
        return out.toString().strip();
    }

    private static String load(String name) {
        try (InputStream in = SqliteDatabase.class.getResourceAsStream("/db/migration/" + name)) {
            if (in == null) {
                throw new VaultException(ErrorCode.STORAGE_FAILURE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new VaultException(ErrorCode.STORAGE_FAILURE, e);
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            connection.close();
        } catch (SQLException e) {
            throw new VaultException(ErrorCode.STORAGE_FAILURE, e);
        } finally {
            lock.unlock();
        }
    }
}
