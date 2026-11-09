import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.firetvlite.iptv"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.firetvlite.iptv"
        minSdk = 28 // Fire OS 7 (Android 9): Fire TV Stick 4K Max, 1st generation
        targetSdk = 35
        // GitHub's run number only ever increases, so every new build can update the installed one.
        // The offset keeps this repository's builds above those of the earlier 1.x repository.
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "2.0.0-alpha.1"
    }

    // A dedicated key for this app only. It is committed on purpose (it is not a secret) so that
    // every build has the same signature and sideloaded updates install over each other.
    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "firelite"
            keyAlias = "firelite"
            keyPassword = "firelite"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    // Lint becomes a CI gate in a later package; until then it must not block the release build.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
}
