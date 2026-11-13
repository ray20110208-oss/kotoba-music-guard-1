plugins { id("com.android.application") }

android {
    namespace = "dev.codevibes.kotoba.probe"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.codevibes.kotoba.probe"
        minSdk = 29
        targetSdk = 35
        versionCode = 14
        versionName = "0.3.0-alpha1-realtime"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    androidResources { noCompress += "tflite" }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(files("libs/android-vad-webrtc-2.0.10.aar"))
    implementation(fileTree("libs/yamnet-runtime") { include("*.aar", "*.jar") })
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
}
