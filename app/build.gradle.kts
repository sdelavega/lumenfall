// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
import java.io.ByteArrayOutputStream
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.tasks.Exec
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing identity. `keystore.properties` + `release.jks` live at the
// repo root and are gitignored — the keystore is the app's permanent identity,
// so it must be backed up privately, never committed. When absent (fresh
// checkout, CI), the release build is left UNSIGNED (there is no debug-key
// fallback): the shipped APK is CI's Linux build, signed afterwards on the
// maintainer's machine by `signPrebuiltRelease` below.
// -Plumenfall.noKeystore makes the file invisible even when present, to build an
// unsigned release locally (e.g. to compare with CI) without touching the keystore.
val keystoreProps: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.exists() && !providers.gradleProperty("lumenfall.noKeystore").isPresent }
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
            // AGP would embed META-INF/version-control-info.textproto (the git commit,
            // or NO_VALID_GIT_FOUND from a worktree). That differs between a clone and a
            // copy, so it would break reproducible builds; `repro` inherits this.
            vcsInfo {
                include = false
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (keystoreProps != null) {
                signingConfigs.getByName("release")
            } else {
                null
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

// Signs an already-built (CI's) unsigned release APK with the release key, without
// rebuilding: apksigner v2/v3 signatures live in a block outside the zip entries,
// so the signed APK's contents stay byte-identical to the unsigned one. v1 (JAR)
// signing is off (it would add META-INF entries; minSdk 33 does not need it).
// The passwords go only into the child process's environment; they are never on a
// command line, echoed or logged, and the tool's output is shown only on failure.
// Usage: ./gradlew :app:signPrebuiltRelease -PunsignedApk=<in> -PsignedApk=<out>
abstract class SignPrebuiltTask @Inject constructor(private val execOps: ExecOperations) :
    DefaultTask() {
    @get:Internal abstract val unsignedApk: Property<String>
    @get:Internal abstract val signedApk: Property<String>
    @get:Internal abstract val sdkDir: Property<String>
    @get:Internal abstract val storeFile: Property<String>
    @get:Internal abstract val keyAlias: Property<String>
    @get:Internal abstract val storePassword: Property<String>
    @get:Internal abstract val keyPassword: Property<String>

    private fun apksigner(): String {
        val versionKey = { n: String -> n.split('.', '-').map { it.toIntOrNull() ?: 0 } }
        val newest = File(sdkDir.get(), "build-tools").listFiles()
            ?.filter { File(it, "apksigner").canExecute() }
            ?.sortedWith { a, b ->
                val x = versionKey(a.name)
                val y = versionKey(b.name)
                (0 until maxOf(x.size, y.size)).map { (x.getOrElse(it) { 0 }).compareTo(y.getOrElse(it) { 0 }) }
                    .firstOrNull { it != 0 } ?: 0
            }
            ?.lastOrNull()
            ?: throw GradleException("apksigner not found under ${sdkDir.get()}/build-tools")
        return File(newest, "apksigner").absolutePath
    }

    private fun run(env: Map<String, String>, vararg args: String) {
        val out = ByteArrayOutputStream()
        val result = execOps.exec {
            commandLine(*args)
            environment(env)
            standardOutput = out
            errorOutput = out
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) {
            throw GradleException("apksigner failed (exit ${result.exitValue}):\n$out")
        }
    }

    @TaskAction
    fun sign() {
        val tool = apksigner()
        val input = File(unsignedApk.get())
        val output = File(signedApk.get())
        if (!input.isFile) throw GradleException("unsigned APK not found: $input")
        output.absoluteFile.parentFile.mkdirs()
        output.delete()
        run(
            mapOf(
                "LUMENFALL_KS_PASS" to storePassword.get(),
                "LUMENFALL_KEY_PASS" to keyPassword.get(),
            ),
            tool, "sign",
            "--ks", storeFile.get(),
            "--ks-key-alias", keyAlias.get(),
            "--ks-pass", "env:LUMENFALL_KS_PASS",
            "--key-pass", "env:LUMENFALL_KEY_PASS",
            // Keep every entry where the unsigned APK has it: F-Droid copies the signature
            // onto its own (identical) unsigned build, and re-aligning would break the digest.
            "--alignment-preserved",
            "--v1-signing-enabled", "false",
            "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "true",
            "--out", output.absolutePath,
            input.absolutePath,
        )
        run(emptyMap(), tool, "verify", output.absolutePath)
        logger.lifecycle("signed and verified: $output")
    }
}

tasks.register<SignPrebuiltTask>("signPrebuiltRelease") {
    group = "release"
    description = "Signs CI's unsigned release APK with the release key (-PunsignedApk, -PsignedApk)"
    val props = keystoreProps
    val unsigned = providers.gradleProperty("unsignedApk")
    val signed = providers.gradleProperty("signedApk")
    doFirst {
        if (props == null) {
            throw GradleException(
                "signPrebuiltRelease needs keystore.properties (and release.jks) at the repo root, " +
                    "and must not run with -Plumenfall.noKeystore."
            )
        }
        if (!unsigned.isPresent || !signed.isPresent) {
            throw GradleException("pass -PunsignedApk=<path> and -PsignedApk=<path>")
        }
    }
    if (props != null) {
        // Relative paths are relative to the repository root.
        unsignedApk.set(unsigned.map { rootProject.file(it).absolutePath })
        signedApk.set(signed.map { rootProject.file(it).absolutePath })
        sdkDir.set(android.sdkDirectory.absolutePath)
        storeFile.set(rootProject.file(props.getProperty("storeFile")).absolutePath)
        keyAlias.set(props.getProperty("keyAlias"))
        storePassword.set(props.getProperty("storePassword"))
        keyPassword.set(props.getProperty("keyPassword"))
    }
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
