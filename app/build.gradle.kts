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
        versionCode = 43
        versionName = "1.15.5"

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
        getByName("debug") {
            val userDebugKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
            if (userDebugKeystore.exists()) {
                storeFile = userDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
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

// Hardening: a local release build must FAIL when production signing material
// is absent instead of silently emitting a debug-signed 'release' APK.
// GitHub Actions sets CI=true and re-signs the assembled APK from repository
// secrets (see .github/workflows/release.yml), so the CI pipeline is unchanged.
if (!hasReleaseSigning && System.getenv("CI").isNullOrEmpty()) {
    tasks.register("checkReleaseSigningConfigured") {
        doFirst {
            throw GradleException(
                "Release signing is not configured. Set release.keystore.path, " +
                    "release.keystore.password, release.key.alias and release.key.password " +
                    "in local.properties. Production releases are signed in GitHub Actions " +
                    "from repository secrets; unconfigured local release builds are blocked " +
                    "to avoid accidentally distributing a debug-signed artifact."
            )
        }
    }
    tasks.matching { it.name == "preReleaseBuild" }.configureEach {
        dependsOn("checkReleaseSigningConfigured")
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
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.auth)
    kapt(libs.androidx.room.compiler)
    
    // Offline speech-to-text engine (SenseVoice-Small via sherpa-onnx)
    implementation(files("libs/sherpa-onnx-1.13.2.aar"))
    
    // Experimental offline streaming engine (Moonshine v2 isolated native runtime)
    implementation(files("libs/moonshine-voice-0.1.5-isolated.aar"))
    
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

    // Instrumentation Testing
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}
