package com.codewitheli.vaultlocal.application.service;

import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.ActivityLogQuery;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.SettingsUseCase;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.Theme;
import com.codewitheli.vaultlocal.application.port.out.Repositories.ActivityLogRepository;
import com.codewitheli.vaultlocal.application.port.out.Repositories.SettingsRepository;
import com.codewitheli.vaultlocal.domain.audit.ActivityEvent;
import com.codewitheli.vaultlocal.domain.policy.AutoLockPolicy;
import com.codewitheli.vaultlocal.domain.policy.StalenessPolicy;

import java.util.List;

/** Non-secret preferences and the activity log viewer. */
public final class SettingsService implements SettingsUseCase, ActivityLogQuery {

    static final String AUTO_LOCK = "auto_lock_minutes";
    static final String STALE_DAYS = "stale_days";
    static final String THEME = "theme";

    private final SettingsRepository settings;
    private final ActivityLogRepository activity;

    public SettingsService(SettingsRepository settings, ActivityLogRepository activity) {
        this.settings = settings;
        this.activity = activity;
    }

    @Override
    public int autoLockMinutes() {
        return AutoLockPolicy.clamp(intSetting(AUTO_LOCK, AutoLockPolicy.DEFAULT_MINUTES));
    }

    @Override
    public void setAutoLockMinutes(int minutes) {
        settings.put(AUTO_LOCK, Integer.toString(AutoLockPolicy.clamp(minutes)));
    }

    @Override
    public int staleDays() {
        return clampDays(intSetting(STALE_DAYS, StalenessPolicy.DEFAULT_DAYS));
    }

    @Override
    public void setStaleDays(int days) {
        settings.put(STALE_DAYS, Integer.toString(clampDays(days)));
    }

    @Override
    public Theme theme() {
        try {
            return settings.get(THEME).map(Theme::valueOf).orElse(Theme.SYSTEM);
        } catch (IllegalArgumentException e) {
            return Theme.SYSTEM;
        }
    }

    @Override
    public void setTheme(Theme theme) {
        settings.put(THEME, theme.name());
    }

    @Override
    public List<ActivityEvent> recentActivity(int limit) {
        return activity.recent(limit);
    }

    private int intSetting(String key, int fallback) {
        try {
            return settings.get(key).map(Integer::parseInt).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clampDays(int days) {
        return Math.max(StalenessPolicy.MIN_DAYS, Math.min(StalenessPolicy.MAX_DAYS, days));
    }
}
