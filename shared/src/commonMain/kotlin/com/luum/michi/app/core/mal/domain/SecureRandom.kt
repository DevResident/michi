package com.luum.michi.app.core.mal.domain

/**
 * Cryptographically secure random bytes (SecureRandom on Android,
 * SecRandomCopyBytes on iOS). Never use `kotlin.random.Random` for PKCE.
 */
internal expect fun secureRandomBytes(size: Int): ByteArray
