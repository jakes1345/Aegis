import java.util.Properties

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

/**
 * The NDK cargo-ndk should use: ANDROID_NDK_HOME if set, else the runner's
 * ANDROID_NDK_LATEST_HOME, else the newest NDK installed under the SDK.
 * cargo-ndk fails with "Error detecting NDK version for path" when it is
 * handed nothing, which is what an unset variable looks like in CI.
 */
fun resolveNdkDir(): File? {
    listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK_LATEST_HOME")
        .mapNotNull { System.getenv(it)?.takeIf { v -> v.isNotBlank() } }
        .map { File(it) }
        .firstOrNull { it.isDirectory }
        ?.let { return it }
    val sdk = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
        .mapNotNull { System.getenv(it)?.takeIf { v -> v.isNotBlank() } }
        .map { File(it) }
        .firstOrNull { it.isDirectory }
        ?: rootProject.file("local.properties").takeIf { it.isFile }?.let { propsFile ->
            val props = Properties()
            propsFile.inputStream().use { stream -> props.load(stream) }
            props.getProperty("sdk.dir")?.let { dir -> File(dir) }
        }
    return sdk?.resolve("ndk")?.listFiles { f -> f.isDirectory && f.resolve("source.properties").isFile }
        ?.maxByOrNull { it.name }
}

val cargoNdkBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Cross-compiles comms-crypto for every Android ABI with cargo-ndk"
    workingDir = rustCrate
    inputs.dir(rustCrate.resolve("src"))
    inputs.files(rustCrate.resolve("Cargo.toml"), rustCrate.resolve("Cargo.lock"))
    outputs.dir(rustJniLibs)
    doFirst {
        val ndk = resolveNdkDir()
            ?: throw GradleException("No Android NDK found: set ANDROID_NDK_HOME or install one under \$ANDROID_HOME/ndk")
        environment("ANDROID_NDK_HOME", ndk.absolutePath)
        logger.lifecycle("cargo-ndk using NDK at ${ndk.absolutePath}")
    }
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

// The JVM unit tests load the same crate built for this machine, so the relay
// round-trip tests exercise the real Olm and sealing code rather than a stand-in.
val rustHostLib = rustCrate.resolve("target/release")
val cargoHostBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds comms-crypto for the build machine, for the JVM unit tests"
    workingDir = rustCrate
    inputs.dir(rustCrate.resolve("src"))
    inputs.files(rustCrate.resolve("Cargo.toml"), rustCrate.resolve("Cargo.lock"))
    outputs.dir(rustHostLib)
    commandLine("cargo", "build", "--release", "--lib")
}

tasks.withType<Test>().configureEach {
    dependsOn(cargoHostBuild)
    systemProperty("jna.library.path", rustHostLib.absolutePath)
    // The relay round-trip tests run only when a relay and enrollment secret are
    // given in the environment (AEGIS_RELAY_URL, AEGIS_ENROLL_SECRET); they are
    // forwarded from the build's own environment and never stored in the repo.
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

android {
    namespace = "com.xat.aegis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xat.aegis"
        minSdk = 31
        targetSdk = 35
        versionCode = 16
        versionName = "2.3.0"
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

    testOptions {
        // android.util.Log and friends return defaults on the JVM instead of throwing.
        unitTests.isReturnDefaultValues = true
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
    // Calls: prebuilt libwebrtc (audio over DTLS-SRTP, signalled through the encrypted envelopes).
    implementation("io.getstream:stream-webrtc-android:1.3.10")

    testImplementation("junit:junit:4.13.2")
    // The real org.json: android.jar only carries stubs of it.
    testImplementation("org.json:json:20240303")
    // The desktop JNA jar carries the native dispatch library for the build machine.
    testImplementation("net.java.dev.jna:jna:5.19.1")
}
