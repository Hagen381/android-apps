plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.molloy.regenradar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.molloy.regenradar"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Fester Schlüssel aus dem GitHub-Secret SIGNING_KEYSTORE_B64 (der Workflow legt ihn als Datei ab).
    // Nur mit gleichem Schlüssel installiert Android Updates über die vorhandene App.
    val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("molloy") {
                storeFile = file(keystorePath)
                storePassword = "android"
                keyAlias = "molloy"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("molloy")
        }
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
    implementation("androidx.work:work-runtime:2.9.1")
}
