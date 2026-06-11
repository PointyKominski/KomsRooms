plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.EdgeRip.KomsRooms"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.EdgeRip.KomsRooms"
        minSdk = 23
        targetSdk = 34
        // Build number auto-increments from GitHub Actions run number.
        // Local builds show versionCode=0, versionName="1.0.0-local"
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = runNumber
        versionName = if (runNumber > 0) "1.0.$runNumber" else "1.0.0-local"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // MUST be true: snapclient is run as a subprocess, so it needs to be
        // a real file on disk. false (the default) keeps .so compressed inside
        // the APK and never extracts it, which means ProcessBuilder can't run it.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Lifecycle / ViewModel / coroutines
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // TV (Leanback for Chromecast / Android TV)
    implementation("androidx.leanback:leanback:1.0.0")

    // RecyclerView for server list
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Album art loading
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // Palette — extract dominant colour from album art for dynamic backgrounds
    implementation("androidx.palette:palette-ktx:1.0.0")
}
