package com.brightpath.channel.service;

import jakarta.inject.Singleton;
import org.apache.commons.codec.digest.DigestUtils;

/**
 * Front-desk kiosk access. The reception terminal keeps the shared desk code in
 * a browser cookie and presents it to reach the arrivals board; the code is
 * checked against the stored digest seeded for the school.
 */
@Singleton
public class DeskAccessService {

    private final SettingsService settings;

    public DeskAccessService(SettingsService settings) {
        this.settings = settings;
    }

    /** True when the presented desk code matches the stored digest. */
    public boolean permits(String deskCode) {
        String digest = DigestUtils.md5Hex(deskCode == null ? "" : deskCode);
        return digest.equals(settings.get("desk_access_digest", ""));
    }
}
