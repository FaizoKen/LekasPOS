import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

// Release signing: keystore.properties (git-ignored) at the repo root, see docs/BUILD.md.
// keyKind = "test" (shared test key: CI + testers' devices) or "upload" (Google Play upload
// key). Without the file, release builds are signed with the per-machine debug key — fine
// on an emulator, never for testers' phones or Google Play.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null
val signingKind = if (hasReleaseKey) keystoreProps.getProperty("keyKind", "upload") else "debug"

// CI passes its run number so every test build has a higher versionCode (installs as an
// update) and a name testers can quote in bug reports, e.g. "0.1.0-ci.42".
val ciRun: String? = providers.gradleProperty("lekas.ciRun").orNull

android {
    namespace = "com.lekaspos"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lekaspos.app"
        minSdk = 21
        targetSdk = 36
        versionCode = ciRun?.toInt() ?: 1
        versionName = "0.5.0" + (ciRun?.let { "-ci.$it" } ?: "")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        // English (default) + Bahasa Melayu; strips other languages pulled in by libraries.
        localeFilters += listOf("en", "ms")
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlin.test.junit)
}
