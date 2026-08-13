import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { 
            load(it) 
        }
    }
}

val hasReleaseSigning = localProperties.getProperty("release.keystore.path") != null &&
        localProperties.getProperty("release.keystore.password") != null &&
        localProperties.getProperty("release.key.alias") != null &&
        localProperties.getProperty("release.key.password") != null

android {
    namespace = "com.groq.voicetyper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.groq.voicetyper"
        minSdk = 26
        targetSdk = 34
        versionCode = 34
        versionName = "1.11.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(localProperties.getProperty("release.keystore.path"))
                storePassword = localProperties.getProperty("release.keystore.password")
                this.keyAlias = localProperties.getProperty("release.key.alias")
                this.keyPassword = localProperties.getProperty("release.key.password")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug.ui"
            versionNameSuffix = "-ui-polish"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation(libs.androidx.activity.compose)
    
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.material.icons.extended)
    
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)
    implementation("androidx.compose.ui:ui-text-google-fonts")
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    kapt(libs.androidx.room.compiler)
    
    // Offline speech-to-text engine (SenseVoice-Small via sherpa-onnx)
    implementation(files("libs/sherpa-onnx-1.13.2.aar"))
    
    debugImplementation(libs.androidx.compose.ui.tooling)
    
    // Unit Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    // Real org.json implementation: the mockable android.jar stubs org.json with
    // "Stub!" throwers, but the streaming transcriber parses server frames with it
    // in production, so the transport tests need the real classes.
    testImplementation("org.json:json:20231013")
}
