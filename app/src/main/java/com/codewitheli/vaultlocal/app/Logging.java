package com.codewitheli.vaultlocal.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * java.util.logging to rotating local files only (1 MB × 3). There is no network handler.
 * Callers log fixed event text and exception class names; never input or secrets (DESIGN.md §6.7).
 */
public final class Logging {

    private Logging() {
    }

    public static void configure(Path logDir) throws IOException {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$-7s %3$s: %5$s%n");
        LogManager.getLogManager().reset();
        Logger root = Logger.getLogger("");
        FileHandler file = new FileHandler(logDir.resolve("vaultlocal.%g.log").toString(), 1_000_000, 3, true);
        file.setFormatter(new SimpleFormatter());
        file.setLevel(Level.INFO);
        root.addHandler(file);
        Handler console = new ConsoleHandler();
        console.setLevel(Level.WARNING);
        root.addHandler(console);
        root.setLevel(Level.INFO);
        // Third-party libraries stay quiet unless something is wrong.
        Logger.getLogger("javafx").setLevel(Level.WARNING);
        Logger.getLogger("org.sqlite").setLevel(Level.WARNING);
    }
}
