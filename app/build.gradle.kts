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
        versionCode = 2
        versionName = "0.2-protocol-foundation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("dev.rikka.shizuku:api:12.1.0")
    implementation("dev.rikka.shizuku:provider:12.1.0")
    testImplementation("junit:junit:4.13.2")
}
