package com.codewitheli.vaultlocal.adapter.ui.os;

import java.awt.Desktop;
import java.awt.desktop.ScreenSleepEvent;
import java.awt.desktop.ScreenSleepListener;
import java.awt.desktop.SystemSleepEvent;
import java.awt.desktop.SystemSleepListener;
import java.awt.desktop.UserSessionEvent;
import java.awt.desktop.UserSessionListener;

/**
 * {@code java.awt.Desktop} app events, where the platform supports them (mainly macOS): system
 * sleep, screen sleep, and user-session switch. Windows uses {@link WindowsSessionLockSource}.
 */
public final class DesktopSessionLockSource implements SessionLockSource {

    @Override
    public void start(Runnable onLock) {
        try {
            if (!Desktop.isDesktopSupported()) {
                return;
            }
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.APP_EVENT_SYSTEM_SLEEP)) {
                desktop.addAppEventListener(new SystemSleepListener() {
                    @Override
                    public void systemAboutToSleep(SystemSleepEvent e) {
                        onLock.run();
                    }

                    @Override
                    public void systemAwoke(SystemSleepEvent e) {
                    }
                });
            }
            if (desktop.isSupported(Desktop.Action.APP_EVENT_SCREEN_SLEEP)) {
                desktop.addAppEventListener(new ScreenSleepListener() {
                    @Override
                    public void screenAboutToSleep(ScreenSleepEvent e) {
                        onLock.run();
                    }

                    @Override
                    public void screenAwoke(ScreenSleepEvent e) {
                    }
                });
            }
            if (desktop.isSupported(Desktop.Action.APP_EVENT_USER_SESSION)) {
                desktop.addAppEventListener(new UserSessionListener() {
                    @Override
                    public void userSessionDeactivated(UserSessionEvent e) {
                        onLock.run();
                    }

                    @Override
                    public void userSessionActivated(UserSessionEvent e) {
                    }
                });
            }
        } catch (RuntimeException | Error e) {
            System.getLogger("vaultlocal.os").log(System.Logger.Level.WARNING,
                    "Desktop app events unavailable: " + e.getClass().getName());
        }
    }

    @Override
    public void close() {
    }
}
