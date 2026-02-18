plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.rdr.youtube2"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.rdr.youtube2"
        minSdk = 16
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
}

configurations.configureEach {
    resolutionStrategy {
        force(
            "com.squareup.okhttp3:okhttp:3.12.13",
            "com.squareup.okhttp3:logging-interceptor:3.12.13",
            "com.squareup.okio:okio:1.17.5"
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.swipe.refresh.layout)

    // Networking

    implementation(libs.gson)
    implementation(libs.okhttp.legacy)
    implementation(libs.okhttp.logging.legacy)
    implementation(libs.okio.legacy)

    // Image loading
    implementation(libs.glide)

    // Native video playback
    implementation(libs.exoplayer)

    // Coroutines
    implementation(libs.coroutines.android)
    implementation(libs.lifecycle.runtime)

    // Android 4.1 support when method count exceeds 65K
    implementation(libs.androidx.multidex)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
