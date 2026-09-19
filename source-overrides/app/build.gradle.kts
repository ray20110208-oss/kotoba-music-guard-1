plugins { id("com.android.application") }

android {
    namespace = "dev.codevibes.kotoba.probe"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.codevibes.kotoba.probe"
        minSdk = 29
        targetSdk = 35
        versionCode = 13
        versionName = "0.2.5-alpha5-battery1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    androidResources { noCompress += "tflite" }
}

dependencies {
    implementation(files("libs/android-vad-webrtc-2.0.10.aar"))
    implementation(fileTree("libs/yamnet-runtime") { include("*.aar", "*.jar") })
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
}
