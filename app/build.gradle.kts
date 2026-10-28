plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.local.wxbotprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.local.wxbotprobe"
        minSdk = 33
        targetSdk = 35
        versionCode = 154
        versionName = "1.0.130"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    lint {
        // This side-loaded build targets the arm64 test phone, not ChromeOS.
        disable += "ChromeOsAbiSupport"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    flavorDimensions += "transport"
    productFlavors {
        create("routeC") {
            dimension = "transport"
            versionNameSuffix = "-route-c-clean"
        }
        create("routeB") {
            dimension = "transport"
            versionNameSuffix = "-route-b-fallback"
        }
    }
}

dependencies {
    implementation(files("libs/OcrLibrary-1.3.0-release.aar"))
    add("routeBImplementation", "dev.rikka.shizuku:api:13.1.5")
    add("routeBImplementation", "dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
