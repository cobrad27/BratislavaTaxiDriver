plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "sk.david.retrohodiny"
    compileSdk = 34

    defaultConfig {
        applicationId = "sk.david.retrohodiny"
        // Android 4.0+ (stare tablety)
        minSdk = 14
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        // vektorove ikony sa pre stare Androidy prevedu na PNG
        vectorDrawables.generatedDensities("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
    }

    signingConfigs {
        getByName("debug") {
            // v1 (JAR) podpis je nutny pre Android < 7
            enableV1Signing = true
            enableV2Signing = true
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
