package michi.versioning

/** Version values derived from the latest reachable `vX.Y.Z` git tag. */
data class AppVersion(
    val versionName: String,
    val versionCode: Int,
    /** Apple only accepts `X.Y.Z` in `CFBundleShortVersionString`. */
    val iosMarketingVersion: String,
)

/** Result of resolving the tag and any command-line overrides. */
data class VersionResolution(
    val version: AppVersion,
    val warning: String? = null,
)

object AppVersionCalculator {

    const val FALLBACK_NAME = "0.0.0-unknown"
    const val FALLBACK_CODE = 1

    private val DESCRIBE_PATTERN =
        Regex("""^v(\d+)\.(\d+)\.(\d+)-(\d+)-g([0-9a-fA-F]+)$""")

    private val SEMVER_PATTERN =
        Regex("""^(\d+)\.(\d+)\.(\d+)(?:[+-].*)?$""")

    /**
     * Resolves the version from `git describe --tags --match 'v[0-9]*' --long`.
     *
     * @param describe output of `git describe`, or null when git/the tag is unavailable.
     * @param overrideName value of `-PmichiVersionName`, if present.
     * @param overrideCode value of `-PmichiVersionCode`, if present.
     */
    fun resolve(
        describe: String?,
        overrideName: String? = null,
        overrideCode: String? = null,
    ): VersionResolution {
        val tagVersion = describe?.trim()?.let(::parseDescribe)
        val nameOverride = overrideName?.takeIf { it.isNotBlank() }
        val codeOverride = overrideCode
            ?.takeIf { it.isNotBlank() }
            ?.let { value ->
                value.toIntOrNull()
                    ?: throw IllegalArgumentException(
                        "Invalid -PmichiVersionCode='$value'. It must be an integer.",
                    )
            }

        if (tagVersion == null && nameOverride == null && codeOverride == null) {
            return VersionResolution(
                version = fallback(),
                warning = "no reachable vX.Y.Z git tag found; using $FALLBACK_NAME " +
                    "and versionCode $FALLBACK_CODE.",
            )
        }

        val versionName = nameOverride ?: tagVersion?.versionName ?: FALLBACK_NAME
        val versionCode = codeOverride ?: tagVersion?.versionCode ?: FALLBACK_CODE
        val marketingVersion = marketingVersionFor(versionName, tagVersion)
        val warning = if (tagVersion == null) {
            "no reachable vX.Y.Z git tag found; using the command-line override(s)."
        } else {
            null
        }

        return VersionResolution(
            version = AppVersion(
                versionName = versionName,
                versionCode = versionCode,
                iosMarketingVersion = marketingVersion,
            ),
            warning = warning,
        )
    }

    private fun parseDescribe(describe: String): AppVersion? {
        val match = DESCRIBE_PATTERN.matchEntire(describe) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        val patch = match.groupValues[3].toIntOrNull() ?: return null
        val distance = match.groupValues[4].toIntOrNull() ?: return null
        val sha = match.groupValues[5]

        val base = "$major.$minor.$patch"
        // Build metadata keeps the tag's base version and sorts after the tag
        // in semver-aware tooling; a "-dev" prerelease would sort before it.
        val name = if (distance == 0) base else "$base+$distance.$sha"
        return AppVersion(
            versionName = name,
            versionCode = versionCode(major, minor, patch),
            iosMarketingVersion = base,
        )
    }

    private fun fallback() = AppVersion(
        versionName = FALLBACK_NAME,
        versionCode = FALLBACK_CODE,
        iosMarketingVersion = "0.0.0",
    )

    /**
     * `X * 10000 + Y * 100 + Z`. Fails instead of silently colliding when a
     * component does not fit the two-digit minor/patch slots.
     */
    fun versionCode(major: Int, minor: Int, patch: Int): Int {
        if (minor !in 0..99 || patch !in 0..99) {
            throw IllegalArgumentException(
                "Unsupported version $major.$minor.$patch: minor and patch must be " +
                    "between 0 and 99 so the versionCode formula stays collision-free.",
            )
        }
        val code = major.toLong() * 10_000L + minor * 100L + patch
        if (code < 1L || code > 2_100_000_000L) {
            throw IllegalArgumentException(
                "Unsupported version $major.$minor.$patch: versionCode $code is outside " +
                    "the Play Store range 1..2,100,000,000.",
            )
        }
        return code.toInt()
    }

    private fun marketingVersionFor(name: String, tagVersion: AppVersion?): String {
        val match = SEMVER_PATTERN.matchEntire(name) ?: return tagVersion?.iosMarketingVersion ?: "0.0.0"
        return "${match.groupValues[1]}.${match.groupValues[2]}.${match.groupValues[3]}"
    }
}
