plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bilal.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bilal.tv"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // The capture helper is started by name through app_process, so keep
            // shrinking off (or keep com.bilal.tv.capture.** if you enable it).
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")

    // Subtitles are plain SRT files shown in sync — no Google Play Services,
    // no speech recognition, no cloud translation. Works on AOSP Amlogic boxes.

    testImplementation("junit:junit:4.13.2")
}
