package michi.versioning

import org.gradle.api.provider.Property

/** Version values shared by every module and by the iOS xcconfig task. */
abstract class MichiVersioningExtension {
    abstract val versionName: Property<String>
    abstract val versionCode: Property<Int>
    abstract val iosMarketingVersion: Property<String>
}
