import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.khamimzarr.tabletagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "id.khamimzarr.tabletagent"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"

        val local = Properties().apply {
            file("../local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        val defServer = local.getProperty("agent.server", "ws://43.159.35.225:2198")
        val defToken = local.getProperty("agent.token", "")
        buildConfigField("String", "DEFAULT_SERVER", "\"$defServer\"")
        buildConfigField("String", "DEFAULT_TOKEN", "\"$defToken\"")
    }

    buildFeatures {
        buildConfig = true
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
}
