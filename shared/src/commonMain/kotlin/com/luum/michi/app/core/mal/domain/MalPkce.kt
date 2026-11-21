package com.luum.michi.app.core.mal.domain

private const val VerifierBytes = 48 // 384 bits -> exactly 64 base64url chars
private const val StateBytes = 24 // 192 bits -> exactly 32 base64url chars

internal const val MalVerifierLength = 64
internal const val MalStateLength = 32

/** One PKCE attempt. MAL's `plain` method makes the verifier the challenge. */
internal data class Pkce(val verifier: String, val state: String)

internal fun generatePkce(
    randomBytes: (Int) -> ByteArray = { secureRandomBytes(it) },
): Pkce = Pkce(
    verifier = base64UrlNoPadding(randomBytes(VerifierBytes)),
    state = base64UrlNoPadding(randomBytes(StateBytes)),
)

private const val Alphabet =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

/**
 * Base64url without padding. Its alphabet (`A-Z a-z 0-9 - _`) is a subset of
 * PKCE's allowed verifier characters (`[A-Za-z0-9-._~]`), which is why the
 * random bytes become a valid 64/32-character verifier/state.
 */
internal fun base64UrlNoPadding(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val output = StringBuilder((bytes.size * 4 + 2) / 3)
    var buffer = 0
    var bits = 0
    for (byte in bytes) {
        buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
        bits += 8
        while (bits >= 6) {
            bits -= 6
            output.append(Alphabet[(buffer shr bits) and 0x3F])
        }
    }
    if (bits > 0) {
        output.append(Alphabet[(buffer shl (6 - bits)) and 0x3F])
    }
    return output.toString()
}
