plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.callforwarder.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.callforwarder.app"
        minSdk = 26
        // Kept below 34 so the microphone foreground service can be started from a
        // PHONE_STATE broadcast without Android 14's background-start restrictions.
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.linphone:linphone-sdk-android:5.4.111")
}
