plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun envOrProperty(name: String, fallback: String = ""): String =
    providers.gradleProperty(name).orNull ?: System.getenv(name) ?: fallback

fun quotedBuildConfig(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "app.memento.mobile"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.memento.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 113
        versionName = "1.0.13"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "MEMENTO_AI_BASE_URL",
            quotedBuildConfig(envOrProperty("MEMENTO_AI_BASE_URL", "https://memento.ivann.fun/api/mobile/v1"))
        )
        buildConfigField(
            "String",
            "MEMENTO_APP_BOOTSTRAP_SECRET",
            quotedBuildConfig(envOrProperty("MEMENTO_APP_BOOTSTRAP_SECRET"))
        )
    }
    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a") }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("com.github.wendykierp:JTransforms:3.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
