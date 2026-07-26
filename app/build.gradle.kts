plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "id.gauvin.grouse"
    compileSdk = 34

    defaultConfig {
        applicationId = "id.gauvin.grouse"
        minSdk = 26
        targetSdk = 34
        // MUST be bumped on every sideloaded build. It sat at 1 through many rebuilds, and a
        // same-versionCode install is a reinstall Android may silently skip or refuse -- the
        // installer reports success while the old APK stays in place, so fixes appear not to
        // work and get re-debugged from scratch. versionName carries the date for the same
        // reason: so "which build is this?" is answerable from the About/app-info screen.
        versionCode = 5
        versionName = "0.5-20260725"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideload build: sign with the debug keystore so the release APK installs OVER the
            // debug app (same signature, no uninstall) and needs no separate keystore. The point is
            // isDebuggable=false — that's what removes Compose's debug-mode jank (debug builds skip
            // ART optimization and run Compose instrumented). Not for Play, ideal for personal use.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

// The UnifiedPush connector transitively pulls kotlin-stdlib 2.3.0, whose metadata this project's
// Kotlin 2.0.20 compiler can't read. Pin the stdlib to our compiler's version.
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:2.0.20")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    // Exclude the connector's JVM tink; security-crypto needs tink-android (Android Keystore), so
    // keep only that and bump it high enough for the connector's classes to resolve — otherwise the
    // two Tink artifacts collide (duplicate classes).
    implementation("org.unifiedpush.android:connector:3.3.3") {
        exclude(group = "com.google.crypto.tink", module = "tink")
    }
    implementation("com.google.crypto.tink:tink-android:1.16.0")
    // Markdown rendering for agent output (headers, bold, lists, fenced code).
    implementation("com.halilibo.compose-richtext:richtext-commonmark:0.20.0")
    implementation("com.halilibo.compose-richtext:richtext-ui-material3:0.20.0")
}
