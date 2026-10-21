package com.brightpath.channel.service;

import jakarta.inject.Singleton;

import java.util.Random;

/**
 * Provides the static-asset version string appended as a cache-busting suffix.
 * Non-cryptographic use only: java.util.Random is used solely for the
 * cache-buster and the value is regenerated once per application start.
 */
@Singleton
public class AssetVersionProvider {

    private final String version = "v" + new Random().nextInt(1_000_000);

    public String version() {
        return version;
    }
}
