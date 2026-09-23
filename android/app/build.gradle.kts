plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ── COMMS end-to-end encryption: the Rust crate in ../comms-crypto ─────────
//
// vodozemac (Olm double ratchet) plus the sealed-envelope layer live in Rust and
// are exposed to Kotlin through UniFFI. Two tasks make `gradle assembleDebug`
// self-contained: cargo-ndk cross-compiles the crate into jniLibs, and
// uniffi-bindgen generates the Kotlin binding from the compiled library. Both
// need Rust, cargo-ndk and an NDK on the machine (see .github/workflows/android.yml).

val rustCrate = rootProject.file("../comms-crypto")
val rustJniLibs = layout.buildDirectory.dir("rust/jniLibs")
val rustBindings = layout.buildDirectory.dir("rust/kotlin")
val rustAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

val cargoNdkBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Cross-compiles comms-crypto for every Android ABI with cargo-ndk"
    workingDir = rustCrate
    inputs.dir(rustCrate.resolve("src"))
    inputs.files(rustCrate.resolve("Cargo.toml"), rustCrate.resolve("Cargo.lock"))
    outputs.dir(rustJniLibs)
    commandLine(
        listOf("cargo", "ndk") + rustAbis.flatMap { listOf("-t", it) } +
            listOf("-o", rustJniLibs.get().asFile.absolutePath, "build", "--release", "--lib")
    )
}

val uniffiBindgen by tasks.registering(Exec::class) {
    group = "build"
    description = "Generates the Kotlin binding for comms-crypto from the compiled library"
    dependsOn(cargoNdkBuild)
    workingDir = rustCrate
    inputs.dir(rustJniLibs)
    outputs.dir(rustBindings)
    // The binding metadata is architecture-independent; the arm64 library is read.
    commandLine(
        "cargo", "run", "--quiet", "--release", "--bin", "uniffi-bindgen", "--",
        "generate", "--library", rustJniLibs.get().file("arm64-v8a/libaegis_comms_crypto.so").asFile.absolutePath,
        "--language", "kotlin", "--out-dir", rustBindings.get().asFile.absolutePath
    )
}

tasks.named("preBuild") { dependsOn(uniffiBindgen) }

android {
    namespace = "com.xat.aegis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xat.aegis"
        minSdk = 31
        targetSdk = 35
        versionCode = 11
        versionName = "2.1.0"
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

    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(rustJniLibs)
            kotlin.srcDir(rustBindings)
        }
    }

    // The three ABIs the Rust crate is built for; anything else has no crypto library.
    defaultConfig {
        ndk { abiFilters += rustAbis }
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

    // COMMS — end-to-end encrypted messaging between Aegis apps.
    // OkHttp talks to the owner's relay (signed HTTPS and a WebSocket); JNA is
    // how the UniFFI binding reaches the Rust crypto library; zxing renders and
    // scans the pairing QR codes; the UnifiedPush connector wakes the app through
    // a distributor of the owner's choosing (for example the ntfy app). No Google
    // service is involved anywhere.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("org.unifiedpush.android:connector:3.3.5")
}
