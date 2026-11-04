package com.codewitheli.vaultlocal.adapter.ui.os;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HMODULE;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.platform.win32.WinUser.MSG;
import com.sun.jna.platform.win32.WinUser.WNDCLASSEX;
import com.sun.jna.platform.win32.Wtsapi32;

/**
 * Windows session-lock (Win+L) detection via {@code WTSRegisterSessionNotification} on a hidden
 * window with its own message loop (decision Q10). Purely local Win32 calls through JNA.
 */
public final class WindowsSessionLockSource implements SessionLockSource {

    private static final String WINDOW_CLASS = "VaultLocalSessionMonitor";
    private static final int WTS_SESSION_LOCK = 0x7;

    private volatile HWND hwnd;
    /** Held in a field: JNA callbacks must stay strongly reachable while Windows can call them. */
    private WinUser.WindowProc proc;
    private Thread thread;

    @Override
    public void start(Runnable onLock) {
        thread = new Thread(() -> loop(onLock), "session-lock-monitor");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop(Runnable onLock) {
        try {
            HMODULE module = Kernel32.INSTANCE.GetModuleHandle(null);
            proc = (HWND h, int msg, WPARAM wParam, LPARAM lParam) -> {
                if (msg == WinUser.WM_SESSION_CHANGE && wParam.intValue() == WTS_SESSION_LOCK) {
                    onLock.run();
                    return new LRESULT(0);
                }
                if (msg == WinUser.WM_CLOSE) {
                    Wtsapi32.INSTANCE.WTSUnRegisterSessionNotification(h);
                    User32.INSTANCE.DestroyWindow(h);
                    return new LRESULT(0);
                }
                if (msg == WinUser.WM_DESTROY) {
                    User32.INSTANCE.PostQuitMessage(0);
                    return new LRESULT(0);
                }
                return User32.INSTANCE.DefWindowProc(h, msg, wParam, lParam);
            };
            WNDCLASSEX wc = new WNDCLASSEX();
            wc.hInstance = module;
            wc.lpfnWndProc = proc;
            wc.lpszClassName = WINDOW_CLASS;
            User32.INSTANCE.RegisterClassEx(wc);
            hwnd = User32.INSTANCE.CreateWindowEx(0, WINDOW_CLASS, "", 0, 0, 0, 0, 0, null, null, module, null);
            if (hwnd == null) {
                return;
            }
            Wtsapi32.INSTANCE.WTSRegisterSessionNotification(hwnd, Wtsapi32.NOTIFY_FOR_THIS_SESSION);
            MSG msg = new MSG();
            while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                User32.INSTANCE.TranslateMessage(msg);
                User32.INSTANCE.DispatchMessage(msg);
            }
            User32.INSTANCE.UnregisterClass(WINDOW_CLASS, module);
        } catch (Throwable t) {
            // Native hook unavailable: idle timer and sleep detection still lock the vault.
            System.getLogger("vaultlocal.os").log(System.Logger.Level.WARNING,
                    "Windows session-lock detection unavailable: " + t.getClass().getName());
        }
    }

    @Override
    public void close() {
        HWND h = hwnd;
        if (h != null) {
            User32.INSTANCE.PostMessage(h, WinUser.WM_CLOSE, null, null);
        }
    }
}
