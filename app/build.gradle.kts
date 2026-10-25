plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "it.peppedess.dexreceiver"
    compileSdk = 35
    defaultConfig {
        applicationId = "it.peppedess.dexreceiver"
        minSdk = 29
        targetSdk = 32
        versionCode = 1
        versionName = "0.1-prototype"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
