package com.brightpath.channel.service;

import com.brightpath.channel.domain.Setting;
import com.brightpath.channel.repository.SettingRepository;
import jakarta.inject.Singleton;

import java.util.Optional;

/** Organization-wide settings, seeded in migration V3 and editable by school admins only. */
@Singleton
public class SettingsService {

    private final SettingRepository settings;

    public SettingsService(SettingRepository settings) {
        this.settings = settings;
    }

    public String get(String key, String fallback) {
        return settings.findById(key).map(Setting::getValue).orElse(fallback);
    }

    public int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(get(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean getBool(String key, boolean fallback) {
        return Boolean.parseBoolean(get(key, String.valueOf(fallback)));
    }

    public void set(String key, String value) {
        settings.update(new Setting(key, value));
    }

    public String orgName() {
        return get("org_name", "Brightpath Academy");
    }

    public String supportEmail() {
        return get("support_email", "support@brightpath.example");
    }

    public boolean allowSelfRegistration() {
        return getBool("allow_self_registration", true);
    }

    public int uploadMaxMb() {
        return getInt("upload_max_mb", 25);
    }

    public Optional<String> optional(String key) {
        return settings.findById(key).map(Setting::getValue);
    }
}
