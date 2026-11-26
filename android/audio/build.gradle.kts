plugins { id("com.android.application") }
android {
    namespace = "io.github.bilidownload.audio"
    compileSdk = 36
    defaultConfig { applicationId = "io.github.bilidownload.audio"; minSdk = 29; targetSdk = 36; versionCode = 9; versionName = "0.3.0" }
    buildFeatures { aidl = true }
    sourceSets["main"].aidl.srcDir("../protocol")
    sourceSets["main"].assets.srcDir("build/generated/notices")
    sourceSets["main"].jniLibs.srcDir("build/generated/jniLibs")
    packaging { jniLibs.useLegacyPackaging = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
val verifyAudioTools = tasks.register("verifyAudioTools") {
    doLast {
        val notices = layout.buildDirectory.dir("generated/notices/licenses").get().asFile.apply { mkdirs() }
        val sourceDir = rootProject.file("../.build/audio/arm64-v8a")
        for((src,name) in listOf("ffmpeg/COPYING.LGPLv2.1" to "FFmpeg-LGPL.txt","lame/COPYING" to "LAME-COPYING.txt")) sourceDir.resolve(src).copyTo(notices.resolve(name),overwrite = true)
        listOf("arm64-v8a","armeabi-v7a","x86_64","x86").forEach { abi ->
            val binary = layout.buildDirectory.file("generated/jniLibs/$abi/libaudio-ffmpeg.so").get().asFile
            check(binary.isFile && binary.length() > 0) { "Build the audio processor first with scripts/build-android-audio.sh ($abi)" }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyAudioTools) }
