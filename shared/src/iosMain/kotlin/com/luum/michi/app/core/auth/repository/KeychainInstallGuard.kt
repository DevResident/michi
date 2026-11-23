package com.luum.michi.app.core.auth.repository

import platform.Foundation.NSUserDefaults

/**
 * The Keychain outlives an app uninstall, unlike NSUserDefaults. The marker
 * lives in the (uninstall-cleared) defaults: when it is absent this is a fresh
 * install or a reinstall, so the known token services are wiped before the
 * migration gets a chance to reuse them.
 *
 * The marker is written after the wipe and before migration: a process death
 * in between only causes one more empty wipe, never a migrated token loss.
 */
internal object KeychainInstallGuard {

    internal const val MarkerKey = "michi_keychain_install_marker_v1"

    private val Services = listOf(KeychainServiceAniList, KeychainServiceMal)

    fun clearOnFreshInstall(
        defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
        backend: KeychainBackend = SecItemKeychainBackend(),
        services: List<String> = Services,
    ) {
        if (defaults.boolForKey(MarkerKey)) return
        services.forEach { backend.delete(it, KeychainAccount) }
        defaults.setBool(true, MarkerKey)
        // The marker must be on disk before the migration can write a token;
        // otherwise a crash could clear that fresh token on the next launch.
        defaults.synchronize()
    }
}
