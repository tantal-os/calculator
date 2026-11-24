plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.tantal.calculator"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    ndkVersion = "30.0.16248370"
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        minSdk = 36
        targetSdk = 37
        versionCode = 3
        versionName = "0.2.0"
    }
    sourceSets["main"].jniLibs.directories.add(layout.buildDirectory.dir("rust/jniLibs").get().asFile.path)
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.activity.compose)
    implementation(libs.compose.material3)
    testImplementation(libs.junit)
}

val rustSource = file("src/main/rust")
val rustOutput = layout.buildDirectory.dir("rust")
val nativeLibrary = "libtantal_calculator.so"

fun cargoBuild(name: String, target: String? = null, linker: String? = null) = tasks.register<Exec>(name) {
    workingDir = rustSource
    inputs.dir(rustSource.resolve("src"))
    inputs.files(rustSource.resolve("Cargo.toml"), rustSource.resolve("Cargo.lock"))
    val output = if (target == null) "release/${System.mapLibraryName("tantal_calculator")}"
        else "$target/release/$nativeLibrary"
    outputs.file(rustOutput.map { it.file(output) })
    environment("CARGO_TARGET_DIR", rustOutput.get().asFile)
    commandLine("cargo", "build", "--locked", "--release")
    if (target != null && linker != null) {
        val ndk = androidComponents.sdkComponents.ndkDirectory.get().asFile
        val toolchain = ndk.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")
        environment("CARGO_TARGET_${target.uppercase().replace('-', '_')}_LINKER", toolchain.resolve(linker))
        args("--target", target)
    }
}

val targets = mapOf("arm64-v8a" to "aarch64-linux-android", "x86_64" to "x86_64-linux-android")
val packageRust = tasks.register<Sync>("packageRust") {
    into(rustOutput.map { it.dir("jniLibs") })
}
targets.forEach { (abi, target) ->
    val build = cargoBuild("cargoBuild${target.substringBefore('-')}", target, "${target}36-clang")
    packageRust.configure {
        dependsOn(build)
        from(rustOutput.map { it.file("$target/release/$nativeLibrary") }) { into(abi) }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(packageRust) }

val hostRust = cargoBuild("cargoBuildHost")
tasks.withType<Test>().configureEach {
    dependsOn(hostRust)
    systemProperty("java.library.path", rustOutput.get().dir("release").asFile.absolutePath)
}
