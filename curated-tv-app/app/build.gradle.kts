plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.sloom.curatedtv"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sloom.curatedtv"
        minSdk = 23
        targetSdk = 32
        versionCode = 2
        versionName = "2.0"
        buildConfigField("String", "CONFIG_URL", "\"https://raw.githubusercontent.com/sloom059-svg/images-repo/main/curated-tv/channels.json\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions { isCoreLibraryDesugaringEnabled = true; sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
 implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")
 implementation("androidx.media3:media3-exoplayer:1.8.0")
 implementation("androidx.media3:media3-ui:1.8.0")
 implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
 coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
