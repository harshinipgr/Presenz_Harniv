plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cit.smartattendance"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cit.smartattendance"
        minSdk = 26            // Android 8.0
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    // AndroidX + Material Design 3
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.google.android.material:material:1.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Networking (REST API client)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Camera (CameraX)
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // Google ML Kit: QR scanning + face detection (on-device)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.mlkit:face-detection:16.1.7")

    // TensorFlow Lite for MobileFaceNet face embeddings
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    // GPS location
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // QR code generation on the faculty phone
    implementation("com.google.zxing:core:3.5.3")
}
