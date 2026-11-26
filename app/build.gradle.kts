import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// The signing key comes from the build (GitHub Actions secrets), never from the repository.
val keystorePath: String? = System.getenv("WALLPAPERCHANGER_KEYSTORE")

android {
    namespace = "io.github.bornaradusin.wallpaperchanger"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.bornaradusin.wallpaperchanger"
        minSdk = 31
        targetSdk = 36
        versionCode = 15
        versionName = "1.4.6"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("WALLPAPERCHANGER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("WALLPAPERCHANGER_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "wallpaperchanger"
                keyPassword = System.getenv("WALLPAPERCHANGER_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
                    ?: System.getenv("WALLPAPERCHANGER_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // The map for place rules (OpenStreetMap, no Google key needed)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
