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
        ndk {
            // Typical Android TV receivers are ARM; keeps the Vosk native libs small.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
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

    // On-device translation to Arabic
    implementation("com.google.mlkit:translate:17.0.3")

    // On-device speech recognition (models are downloaded at runtime)
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    testImplementation("junit:junit:4.13.2")
}
