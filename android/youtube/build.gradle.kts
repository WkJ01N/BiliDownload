plugins { id("com.android.application") }

android {
    namespace = "io.github.bilidownload.youtube"
    compileSdk = 36
    defaultConfig { applicationId = "io.github.bilidownload.youtube"; minSdk = 29; targetSdk = 36; versionCode = 7; versionName = "0.2.0" }
    buildFeatures { aidl = true }
    sourceSets["main"].aidl.srcDir("../protocol")
    sourceSets["main"].assets.srcDir("build/generated/engine/assets")
    sourceSets["main"].res.srcDir("build/generated/engine/res")
    packaging { jniLibs.useLegacyPackaging = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

val prepareEngine = tasks.register<Exec>("prepareEngine") {
    inputs.files("../../youtube/bridge.py", "../../scripts/prepare-youtube.py", "../../packaging/youtube.json")
    outputs.dir(layout.buildDirectory.dir("generated/engine"))
    workingDir("../..")
    commandLine("python", "scripts/prepare-youtube.py", "android")
}
tasks.named("preBuild").configure { dependsOn(prepareEngine) }

dependencies { implementation("io.github.junkfood02.youtubedl-android:library:0.18.1") }
