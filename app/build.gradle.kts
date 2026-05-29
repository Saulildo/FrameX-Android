plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    kotlin("kapt")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.framex.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.framex.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        // Native GPU instrumentation layers (Vulkan + GLES). 64-bit + 32-bit so the layer ABI
        // matches whatever the target game runs as.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    // Build the Vulkan/GLES instrumentation layers from src/main/cpp.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // The layer .so files must exist on disk in our nativeLibraryDir so the target app's
    // GraphicsEnvironment can dlopen them when injected.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui:1.6.1")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.1")
    implementation("androidx.compose.material:material-icons-extended:1.6.1")
    
    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")
    
    // Hilt
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Google Fonts
    implementation("androidx.compose.ui:ui-text-google-fonts:1.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling:1.6.1")
}
