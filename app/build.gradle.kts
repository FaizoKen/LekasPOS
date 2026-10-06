import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

// Release signing: keystore.properties (git-ignored) at the repo root, see docs/BUILD.md.
// keyKind = "release" (the app's signing key: CI, the public downloads and testers' devices,
// D-051) or "upload" (Google Play upload key). Without the file, release builds are signed with
// the per-machine debug key — fine on an emulator, never for anyone's phone or Google Play.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null
val signingKind = if (hasReleaseKey) keystoreProps.getProperty("keyKind", "upload") else "debug"

// CI passes its run number as the versionCode, so every build installs over the one before;
// the app shows it as "1.0.0 (build 60)" for bug reports. The public release is the CI build of
// its tagged commit (D-051). Bump versionName when a new release starts.
val ciRun: String? = providers.gradleProperty("lekas.ciRun").orNull

android {
    namespace = "com.lekaspos"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lekaspos.app"
        minSdk = 21
        targetSdk = 36
        versionCode = ciRun?.toInt() ?: 1
        versionName = "1.11.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        // English (default) + Bahasa Melayu; strips other languages pulled in by libraries.
        localeFilters += listOf("en", "ms")
    }

    bundle {
        // The app switches language itself (Settings → App language, D-046): an app bundle must
        // ship both languages to every phone, not only the phone's own (lint AppBundleLocaleChanges).
        language {
            enableSplit = false
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "SIGNING_KEY", "\"debug\"")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
            buildConfigField("String", "SIGNING_KEY", "\"$signingKind\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += listOf(
                "DebugProbesKt.bin",
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "kotlin/**",
            )
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true // also analyzes :core (NewApi for JDK calls missing below API 24)
        checkReleaseBuilds = true
        // Deliberate: targetSdk = Google Play's requirement (36), and library versions are
        // pinned to the last releases that support minSdk 21 (docs/DECISIONS.md D-004, D-005).
        // NewerVersionAvailable: ZXing stays on 3.3.3, the last line without Java 8 APIs (D-025).
        disable += listOf("OldTargetApi", "GradleDependency", "NewerVersionAvailable")
    }

    testOptions {
        animationsDisabled = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core)
    implementation(libs.androidx.recyclerview)
    implementation(libs.zxing.core)
    implementation(libs.androidx.work.runtime)
    implementation(libs.play.services.auth)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlin.test.junit)
}
