// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
import java.util.Properties
import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing identity. `keystore.properties` + `release.jks` live at the
// repo root and are gitignored — the keystore is the app's permanent identity,
// so it must be backed up privately, never committed. When absent (fresh
// checkout, CI), release builds fall back to the debug key so they still
// assemble; they just aren't shippable.
val keystoreProps: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

// --- Rust native core (cargo-ndk) -----------------------------------------
// The Rust workspace (rootProject) builds `liblumenfall_core.so`, which the
// app loads via JNI (see core/LumenfallCore.kt). We keep the explicit cargo-ndk
// path the architecture doc chose over a heavier Gradle Rust plugin.
//
// ABIs: arm64-v8a only. Every device from our minSdk floor (Android 13, 2023+)
// is 64-bit ARM, and Apple-Silicon emulators use the arm64 system image too, so
// this one ABI covers real devices and local emulators. Add "x86_64" here if you
// ever need an Intel emulator.
val rustAbis = listOf("arm64-v8a")
val rustMinApi = 33
val ndkPin = "27.3.13750724" // LTS line; must be installed via sdkmanager
val rustJniLibs = layout.buildDirectory.dir("rustJniLibs")

android {
    namespace = "com.sdelavega.lumenfall"
    compileSdk = 36
    ndkVersion = ndkPin

    defaultConfig {
        applicationId = "com.sdelavega.lumenfall"
        // Android 13. Every device released in 2023 or later shipped with at
        // least this, so it covers the modern install base without excluding it.
        minSdk = 33
        targetSdk = 36
        versionCode = 10000
        versionName = "1.0.0"
        ndk { abiFilters += rustAbis }
    }

    // The cargo-ndk task writes lib*.so here in per-ABI subdirs; treat it as a
    // jniLibs source so the normal merge/packaging picks it up.
    sourceSets["main"].jniLibs.srcDir(rustJniLibs)

    // The encrypted dependency-metadata blob is only useful to Google Play and
    // is a needless extra thing for a reproducible build to carry.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        lintConfig = file("lint.xml")
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (keystoreProps != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        // Unsigned twin of release, used by scripts/repro-check.sh: signing is
        // the only intended difference, so two builds can be compared bytewise.
        create("repro") {
            initWith(getByName("release"))
            signingConfig = null
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

val cargoNdkBuild by tasks.registering(Exec::class) {
    group = "rust"
    description = "Cross-compiles the Rust core to a JNI .so via cargo-ndk"
    workingDir = rootProject.projectDir // Cargo workspace root

    val ndkDir = android.sdkDirectory.resolve("ndk/$ndkPin")
    val outDir = rustJniLibs.get().asFile
    // cargo comes from $CARGO or PATH; rust-toolchain.toml pins the compiler.
    val cargoCmd = System.getenv("CARGO") ?: "cargo"
    val home = System.getProperty("user.home")
    val cargoHome = System.getenv("CARGO_HOME") ?: "$home/.cargo"
    val rustupHome = System.getenv("RUSTUP_HOME") ?: "$home/.rustup"
    // Remap every machine-specific prefix so the .so carries no host paths and
    // two checkouts in different directories build identical bytes.
    val remapFlags = listOf(
        "${rootProject.projectDir.absolutePath}=/lumenfall",
        "$cargoHome=/cargo",
        "$rustupHome=/rustup",
    ).joinToString(" ") { "--remap-path-prefix=$it" }

    // -Plumenfall.prebuiltJni skips this task: the F-Droid recipe builds the .so
    // itself (fdroidserver removes gradlew and Gradle's task history before the
    // build) and passes the property. Normal builds leave it unset and build here.
    val prebuiltJni = providers.gradleProperty("lumenfall.prebuiltJni").isPresent
    val prebuiltSo = outDir.resolve("arm64-v8a/liblumenfall_core.so")
    onlyIf {
        if (prebuiltJni && !prebuiltSo.isFile) {
            throw GradleException(
                "lumenfall.prebuiltJni is set but $prebuiltSo is missing; " +
                    "build it with cargo ndk first, or drop the property."
            )
        }
        !prebuiltJni
    }

    inputs.dir(rootProject.projectDir.resolve("core-rust/src"))
    inputs.file(rootProject.projectDir.resolve("core-rust/Cargo.toml"))
    inputs.file(rootProject.projectDir.resolve("Cargo.toml"))
    inputs.file(rootProject.projectDir.resolve("Cargo.lock"))
    inputs.property("remapFlags", remapFlags)
    outputs.dir(outDir)

    doFirst {
        if (!ndkDir.exists()) {
            throw GradleException("NDK $ndkPin not found at $ndkDir. Install it: sdkmanager \"ndk;$ndkPin\"")
        }
        outDir.mkdirs()
    }
    environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
    environment("RUSTFLAGS", remapFlags)

    val cmd = mutableListOf(cargoCmd, "ndk")
    rustAbis.forEach { cmd += listOf("-t", it) }
    cmd += listOf("--platform", rustMinApi.toString(), "-o", outDir.absolutePath,
        "build", "--release", "--locked", "-p", "lumenfall-core")
    commandLine(cmd)
}

tasks.named("preBuild") { dependsOn(cargoNdkBuild) }

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-ui:1.7.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
