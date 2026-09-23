plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firebase Cloud Messaging wakes the app for texts and calls on the COMMS tab.
// The google-services plugin turns google-services.json into resources and fails
// the build when the file is absent, so it is applied only when the file exists:
// a checkout without it (CI, a fork without a Firebase project) still builds, and
// the app then runs with push off and syncs on open instead.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.xat.aegis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xat.aegis"
        minSdk = 31
        targetSdk = 35
        versionCode = 10
        versionName = "2.0.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Live tile map — renders OpenStreetMap tiles. The network is used only for tile
    // downloads, which expose the device's IP and the viewed area to the tile server;
    // no detection data is transmitted.
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Card vault — BiometricPrompt and AppCompatActivity for vault unlock
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.biometric:biometric:1.1.0")

    // COMMS — HTTPS to the owner's relay Worker, and Firebase Cloud Messaging for
    // wake-ups. The push payload is a kind and a cursor; message content is
    // fetched from the relay over TLS, never sent through Google.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // COMMS calls — Twilio Voice over WebRTC to the owner's number, and audio
    // routing (earpiece, speaker, Bluetooth) for the in-call screen.
    implementation("com.twilio:voice-android:6.10.4")
    implementation("com.twilio:audioswitch:1.2.5")
}
