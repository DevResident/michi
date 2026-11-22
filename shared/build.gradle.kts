import michi.versioning.MichiVersioningExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

val michiVersion = rootProject.extensions.getByType(MichiVersioningExtension::class.java)

// Pin the generated Res package: without this it falls back to a
// name-derived default that has already drifted once (stale outputs under
// a different package broke incremental builds).
compose.resources {
    packageOfResClass = "com.luum.michi.app.resources"
}

abstract class GenerateClientIdBuildConfigTask : DefaultTask() {
    @get:Input
    abstract val clientId: Property<String>

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val objectName: Property<String>

    @get:Input
    abstract val configKey: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val packagePath = packageName.get()
        val packageDir = outputDir.get().asFile.resolve(packagePath.replace('.', '/'))
        packageDir.mkdirs()
        packageDir.resolve("${objectName.get()}.kt").writeText(
            """
            package $packagePath

            /**
             * Generated at build time from `local.properties`.
             * Do not edit by hand. Configure `${configKey.get()}` in your local.properties.
             */
            internal object ${objectName.get()} {
                const val ClientId: String = "${clientId.get()}"
            }
            """.trimIndent() + "\n"
        )
    }
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val anilistClientIdValue: String =
    (localProperties["anilistClientId"] as? String)
        ?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANILIST_CLIENT_ID").orEmpty()

// Optional on purpose: MAL is a secondary login, so an absent client id never
// fails a build; its provider simply stays unregistered (the button is hidden).
val malClientIdValue: String =
    (localProperties["malClientId"] as? String)
        ?.takeIf { it.isNotBlank() }
        ?: System.getenv("MAL_CLIENT_ID").orEmpty()

// Test-only AniList token for the opt-in live suite (see `live` test package).
// local.properties is gitignored; never commit or log this value.
val anilistTestTokenValue: String =
    (localProperties["anilistTestToken"] as? String)
        ?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANILIST_TEST_TOKEN").orEmpty()

// Fail fast when packaging a release without a client id, otherwise the shipped
// binary would bake in an empty ClientId and every user would hit the
// "Missing anilistClientId" screen. Debug builds are intentionally allowed to
// run without it so day-to-day development is not blocked.
val isReleaseBuild = gradle.startParameter.taskNames.any { taskName ->
    taskName.substringAfterLast(':').contains("release", ignoreCase = true)
}
if (isReleaseBuild && anilistClientIdValue.isBlank()) {
    throw GradleException(
        "Missing anilistClientId for a release build. Set `anilistClientId` in " +
            "local.properties or the ANILIST_CLIENT_ID environment variable before " +
            "assembling a release — otherwise shipped users would see the " +
            "\"Missing anilistClientId\" error."
    )
}

val generateAniListBuildConfig = tasks.register<GenerateClientIdBuildConfigTask>("generateAniListBuildConfig") {
    clientId.set(anilistClientIdValue)
    packageName.set("com.luum.michi.app.core.auth.domain")
    objectName.set("AniListBuildConfig")
    configKey.set("anilistClientId")
    outputDir.set(layout.buildDirectory.dir("generated/source/anilistConfig/commonMain/kotlin"))
}

val generateMalBuildConfig = tasks.register<GenerateClientIdBuildConfigTask>("generateMalBuildConfig") {
    clientId.set(malClientIdValue)
    packageName.set("com.luum.michi.app.core.mal.domain")
    objectName.set("MalBuildConfig")
    configKey.set("malClientId")
    outputDir.set(layout.buildDirectory.dir("generated/source/malConfig/commonMain/kotlin"))
}

abstract class GenerateMichiBuildConfigTask : DefaultTask() {
    @get:Input
    abstract val appVersion: Property<String>

    @get:Input
    abstract val appVersionCode: Property<Int>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val packageDir = outputDir.get().asFile.resolve("com/luum/michi/app")
        packageDir.mkdirs()
        packageDir.resolve("MichiBuildConfig.kt").writeText(
            """
            package com.luum.michi.app

            /**
             * Generated at build time from the latest reachable `vX.Y.Z`
             * git tag (`michiVersionName` / `michiVersionCode` overrides).
             * Do not edit by hand.
             */
            internal object MichiBuildConfig {
                const val Version: String = "${appVersion.get()}"
                const val VersionCode: Int = ${appVersionCode.get()}
            }
            """.trimIndent() + "\n"
        )
    }
}

val generateMichiBuildConfig = tasks.register<GenerateMichiBuildConfigTask>("generateMichiBuildConfig") {
    appVersion.set(michiVersion.versionName)
    appVersionCode.set(michiVersion.versionCode)
    outputDir.set(layout.buildDirectory.dir("generated/source/michiConfig/commonMain/kotlin"))
}

kotlin {
    // NOTE: resource accessors (`Res.*`) are generated into
    // `commonMainResourceAccessors` by the compose-resources plugin, but under
    // androidMultiplatformLibrary that source set never reaches the Android
    // compilation (verified via build logs: compileAndroidMain only looks
    // for `androidMainResourceAccessors`, which is never generated). Do NOT
    // re-register that dir into another source set: K2 fragments reject a
    // file belonging to two modules. Icons avoid this entirely (Valkyrie
    // `ImageVector` code, see ui/icons); fonts use a hand-written mirror
    // (see LocalFonts in ui/theme/Typography.kt).
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    android {
       namespace = "com.luum.michi.app.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()

       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.browser)
            implementation(libs.androidx.activity.compose)
            // Android: 1.5.0 stable at compile and runtime. commonMain/iOS keep
            // the JetBrains artifact (1.12.0-alpha03 via material-kolor).
            implementation(libs.androidx.material3)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.coil.network.okhttp)
        }
        commonMain {
            kotlin.srcDir(generateAniListBuildConfig)
            kotlin.srcDir(generateMalBuildConfig)
            kotlin.srcDir(generateMichiBuildConfig)
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.materialIconsCore)
                implementation(libs.compose.ui)
                implementation(libs.compose.components.resources)
                implementation(libs.material.kolor)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.contentNegotiation)
                implementation(libs.ktor.client.logging)
                implementation(libs.ktor.serialization.kotlinxJson)
                implementation(libs.qrose)
                implementation(libs.coil.compose)
            }
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            // Coil has no network fetcher of its own: without this, iOS shows
            // every cover as a placeholder (Android uses the OkHttp one).
            implementation(libs.coil.network.ktor3)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

tasks.withType<Test> {
    environment("ANILIST_TEST_TOKEN", anilistTestTokenValue)
}

