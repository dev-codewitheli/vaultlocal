package com.codewitheli.vaultlocal.app;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;

/**
 * Where the vault lives (DESIGN.md §3.1). Windows uses %LOCALAPPDATA% (never Roaming, which can
 * sync over a domain network). {@code -Dvaultlocal.home=...} overrides it for development and tests.
 */
public final class AppPaths {

    private AppPaths() {
    }

    public static Path dataDir() {
        String override = System.getProperty("vaultlocal.home");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath();
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            return (local != null ? Path.of(local) : Path.of(home, "AppData", "Local")).resolve("VaultLocal");
        }
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "VaultLocal");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        return (xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(home, ".local", "share")).resolve("vaultlocal");
    }

    /** Creates the directory; on POSIX systems restricts it to the owner (0700). */
    public static Path ensure(Path dir) throws IOException {
        Files.createDirectories(dir);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
        return dir;
    }
}
