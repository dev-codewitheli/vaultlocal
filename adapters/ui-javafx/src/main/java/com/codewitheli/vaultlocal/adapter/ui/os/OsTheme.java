package com.codewitheli.vaultlocal.adapter.ui.os;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

/** Best-effort OS dark-mode detection (JavaFX 21 has no API for it). Falls back to light. */
public final class OsTheme {

    private static final String WIN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";

    private OsTheme() {
    }

    public static boolean prefersDark() {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                return Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, WIN_KEY, "AppsUseLightTheme")
                        && Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, WIN_KEY, "AppsUseLightTheme") == 0;
            }
        } catch (RuntimeException | Error e) {
            return false;
        }
        return false;
    }
}
