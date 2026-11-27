import java.security.MessageDigest
import java.util.zip.ZipFile

plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
val mediaRuntime by configurations.creating { isTransitive = false }
val runtimeOutput = layout.buildDirectory.dir("generated/media-libs")
val mediaAssets = layout.buildDirectory.dir("generated/media-assets")
val audioLibraries = rootProject.layout.projectDirectory.dir("audio/build/generated/jniLibs")

android {
    namespace = "io.github.bilidownload"
    compileSdk = 36
    defaultConfig { applicationId = "io.github.bilidownload"; minSdk = 29; targetSdk = 36; versionCode = 14; versionName = "0.4.3"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets["androidTest"].assets.srcDir("build/generated/audioFixtures")
    sourceSets["main"].assets.srcDir(mediaAssets.get().asFile)
    sourceSets["main"].jniLibs.srcDirs(runtimeOutput.get().asFile, audioLibraries.asFile)
    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += "**/libpython.zip.so"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    val enableAbiSplits = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
    splits {
        abi {
            isEnable = enableAbiSplits
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}
dependencies {
    mediaRuntime("io.github.junkfood02.youtubedl-android:library:0.18.1@aar")
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("io.github.kyant0:taglib:1.0.5")
    implementation("com.google.code.gson:gson:2.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    testImplementation("org.json:json:20250517")
    testImplementation("junit:junit:4.13.2")
}

val prepareMediaRuntime by tasks.registering {
    inputs.files(mediaRuntime)
    inputs.dir(audioLibraries)
    outputs.dir(runtimeOutput)
    doLast {
        val aar = mediaRuntime.singleFile
        val checksum = MessageDigest.getInstance("SHA-256").digest(aar.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(checksum == "579b5fb480892b1abc2b218c2089699d52759cc8d7ba256bf876453f0365faef") {
            "Unexpected Android media runtime checksum"
        }
        val root = runtimeOutput.get().asFile
        ZipFile(aar).use { zip ->
            for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) {
                check(audioLibraries.file("$abi/libaudio-ffmpeg.so").asFile.length() > 0) {
                    "Build the media processor first with scripts/build-android-audio.sh ($abi)"
                }
                for (name in listOf("libpython.so", "libpython.zip.so", "libqjs.so")) {
                    val dst = root.resolve("$abi/$name")
                    dst.parentFile.mkdirs()
                    val entry = checkNotNull(zip.getEntry("jni/$abi/$name"))
                    zip.getInputStream(entry).use { input -> dst.outputStream().use(input::copyTo) }
                }
            }
        }
    }
}

val prepareMediaAssets by tasks.registering(Exec::class) {
    inputs.files("../../youtube/bridge.py", "../../youtube/xpost.py", "../../scripts/prepare-youtube.py",
        "../../packaging/youtube.json", "../../.build/audio/arm64-v8a/ffmpeg/COPYING.LGPLv2.1",
        "../../.build/audio/arm64-v8a/lame/COPYING")
    outputs.dir(mediaAssets)
    workingDir("../..")
    commandLine("python", "scripts/prepare-youtube.py", "android-main")
}

tasks.named("preBuild") { dependsOn(prepareMediaRuntime, prepareMediaAssets) }
