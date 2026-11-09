import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Shared by versionCode and by the in-app updater (BuildConfig.VERSION_CODE_OFFSET).
val versionCodeOffset = 100

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
        versionCode = versionCodeOffset + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "2.1.0-alpha.1"

        // The in-app updater reads releases of the repository this build was made in.
        // GITHUB_REPOSITORY is set by GitHub Actions ("owner/repo"); local builds leave it empty,
        // which disables the updater.
        buildConfigField("String", "UPDATE_REPOSITORY", "\"${System.getenv("GITHUB_REPOSITORY") ?: ""}\"")
        buildConfigField("int", "VERSION_CODE_OFFSET", "$versionCodeOffset")
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
        buildConfig = true
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
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // Android's own org.json is only a stub in local unit tests; this is the real implementation.
    testImplementation(libs.json)
}
