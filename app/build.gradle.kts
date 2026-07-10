plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "id.gauvin.goose"
    compileSdk = 34

    defaultConfig {
        applicationId = "id.gauvin.goose"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("androidx.work:work-runtime-ktx:2.9.1")
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
