import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.pleos.pitstop"
    compileSdk = 35
    useLibrary("android.car")
    defaultConfig {
        applicationId = "ai.pleos.pitstop"
        minSdk = 33
        targetSdk = 34
        versionCode = 4
        versionName = "0.4.0"
    }
    val localDebugKey = rootProject.file(".tools/android-user/debug.keystore")
    if (localDebugKey.exists()) signingConfigs.getByName("debug").storeFile = localDebugKey
    val signingFile = rootProject.file("signing.properties")
    if (signingFile.exists()) {
        val props = Properties().apply { signingFile.inputStream().use { load(it) } }
        signingConfigs.create("submission") {
            storeFile = rootProject.file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("submission")
    }
    buildTypes.getByName("release") { isDebuggable = false; isMinifyEnabled = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":pitstop-core"))
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
