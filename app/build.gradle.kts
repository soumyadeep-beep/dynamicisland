plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tara.dynamicisland"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tara.dynamicisland"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            // Debug APKs are signed automatically with the Android debug key,
            // so they install on a phone without any keystore setup.
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // WebViewAssetLoader: serves assets/ over https://appassets.androidplatform.net
    // so the page runs in a secure context (needed for microphone access).
    implementation("androidx.webkit:webkit:1.11.0")
}
