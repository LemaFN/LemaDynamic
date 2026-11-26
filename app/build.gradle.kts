plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "de.lema.hyperisland"
    compileSdk = 35
    val ks = file("debug.keystore")
    if (ks.exists()) signingConfigs.getByName("debug") {
        storeFile = ks
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
        storeType = "pkcs12"
    }
    defaultConfig {
        applicationId = "de.lema.hyperisland"
        minSdk = 26
        targetSdk = 35
        // Jeder CI-Build bekommt eine höhere Versionsnummer, sonst installiert Android ihn teils nicht über die alte App
        versionCode = 10 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "0.4." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
