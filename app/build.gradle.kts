plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val sdk = 34

android {
    compileSdk = sdk
    buildToolsVersion = "34.0.0"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "gm.tieba.tabswitch"
        minSdk = 28
        targetSdk = sdk
        // Published builds come from GitHub Releases. Pin both version fields to the source
        // tree so rebuilding from a release tag yields the same APK and can upgrade over it.
        // The old git-count scheme produced different codes locally and in shallow CI clones.
        versionCode = 4000020
        versionName = "4.0.0-beta.20"
        // Only Tieba 22.13.1.1 (APK built 2026-10-27) and newer are supported. Older 22.0.x
        // and 12.x clients are intentionally out of scope; no version-gated rules remain.
        buildConfigField("String", "TARGET_VERSION", "\"22.13.1.1\"")
        buildConfigField("String", "MIN_VERSION", "\"22.13.1.1\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                // x86_64 matters for emulators (MuMu / AVD): DexKit ships an x86_64 JNI
                // library, and without it the whole deobfuscation pass cannot run there.
                abiFilters("arm64-v8a", "x86_64")
                arguments("-DANDROID_STL=none")
            }
        }
    }
    applicationVariants.all {
        outputs
            .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
            .all { output ->
                output.outputFileName = "TS_${defaultConfig.versionName}_${name}.apk"
                false
            }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
        }
    }
    packaging {
        resources {
            // The modern Xposed manifest lives in Java resources; keep it in the APK.
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
        // Keep x86_64 so DexKit works on x86_64 emulators; 32-bit x86 is no longer
        // supported by LSPosed 2.x and is still excluded.
        jniLibs.excludes.addAll(listOf("**/liblog.so", "/lib/x86/**"))
    }
    buildFeatures {
        prefab = true
        buildConfig = true
    }
    lint {
        checkDependencies = true
    }
    namespace = "gm.tieba.tabswitch"
}

dependencies {
    // Modern libxposed API 102. A module targeting 102 cannot access de.robv.android.xposed.
    compileOnly("io.github.libxposed:api:102.0.0")
    api("androidx.annotation:annotation:1.7.1")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    annotationProcessor("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("org.luckypray:dexkit:2.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("dev.rikka.ndk.thirdparty:cxx:1.2.0")
}

val adbExecutable: String = androidComponents.sdkComponents.adb.get().asFile.absolutePath

tasks.register("restartTieba") {
    doLast {
        exec {
            commandLine(adbExecutable, "shell", "am", "force-stop", "com.baidu.tieba")
        }
        exec {
            commandLine(adbExecutable, "shell", "am", "start", "$(pm resolve-activity --components com.baidu.tieba)")
        }
    }
}

afterEvaluate {
    tasks.named("installDebug").configure {
        finalizedBy(tasks.named("restartTieba"))
    }
}
