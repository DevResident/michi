import michi.versioning.MichiVersioningExtension
import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.composeCompiler)
}

val michiVersion = rootProject.extensions.getByType(MichiVersioningExtension::class.java)

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(projects.shared)

    // Compose entry point: setContent + activity-compose APIs.
    implementation(libs.androidx.activity.compose)

    // Installs the generated Baseline Profile at install time and lets
    // Macrobenchmark's CompilationMode.Partial find it.
    implementation(libs.androidx.profileinstaller)

    // Generates the profile and the startup benchmark for this app.
    baselineProfile(projects.baselineprofile)
}

val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val localStoreFile = (keystoreProperties["storeFile"] as? String)?.takeIf { it.isNotBlank() }
val envStoreFile = providers.environmentVariable("MICHI_KEYSTORE_FILE")
    .orNull
    ?.takeIf { it.isNotBlank() }
val useLocalKeystore = localStoreFile != null

val signingStoreFile = when {
    localStoreFile != null -> file(localStoreFile)
    envStoreFile != null -> file(envStoreFile)
    else -> null
}
val signingStorePassword = if (useLocalKeystore) {
    (keystoreProperties["storePassword"] as? String)?.takeIf { it.isNotBlank() }
} else {
    providers.environmentVariable("MICHI_KEYSTORE_PASSWORD").orNull?.takeIf { it.isNotBlank() }
}
val signingKeyAlias = if (useLocalKeystore) {
    (keystoreProperties["keyAlias"] as? String)?.takeIf { it.isNotBlank() }
} else {
    providers.environmentVariable("MICHI_KEY_ALIAS").orNull?.takeIf { it.isNotBlank() }
}
val signingKeyPassword = if (useLocalKeystore) {
    (keystoreProperties["keyPassword"] as? String)?.takeIf { it.isNotBlank() }
} else {
    providers.environmentVariable("MICHI_KEY_PASSWORD").orNull?.takeIf { it.isNotBlank() }
}

if (signingStoreFile != null) {
    if (!signingStoreFile.exists()) {
        throw GradleException(
            "Release keystore not found at ${signingStoreFile.absolutePath}. " +
                "Set storeFile in keystore.properties or MICHI_KEYSTORE_FILE.",
        )
    }
    val missing = buildList {
        if (signingStorePassword == null) add("storePassword / MICHI_KEYSTORE_PASSWORD")
        if (signingKeyAlias == null) add("keyAlias / MICHI_KEY_ALIAS")
        if (signingKeyPassword == null) add("keyPassword / MICHI_KEY_PASSWORD")
    }
    if (missing.isNotEmpty()) {
        throw GradleException(
            "Incomplete release signing configuration; missing: ${missing.joinToString()}. " +
                "Fill keystore.properties or set the MICHI_KEYSTORE_* environment variables.",
        )
    }
}

android {
    namespace = "com.luum.michi.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.luum.michi.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = michiVersion.versionCode.get()
        versionName = michiVersion.versionName.get()
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        create("release") {
            if (signingStoreFile != null) {
                storeFile = signingStoreFile
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}