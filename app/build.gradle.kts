import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// API keys live in secrets.properties (git-ignored). Only the public client_id ships in the app.
val secrets = Properties().apply {
    rootProject.file("secrets.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.rajdeep.listen"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rajdeep.listen"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.5.0"
        // Ship only real-device ABIs; drop x86 to keep APK small.
        // Optional: free key from console.groq.com → fast AI suggestions (else keyless fallback is used).
        buildConfigField("String", "GROQ_API_KEY", "\"${secrets.getProperty("GROQ_API_KEY", "")}\"")
        buildConfigField("String", "JAMENDO_CLIENT_ID", "\"${secrets.getProperty("JAMENDO_CLIENT_ID", "")}\"")
        buildConfigField("String", "ACCOUNT_API_BASE_URL", "\"${secrets.getProperty("ACCOUNT_API_BASE_URL", "https://your-listen-api.example").trimEnd('/')}/\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("release") {
            val keyFile = file("listen.keystore")
            if (keyFile.exists()) {
                storeFile = keyFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { jniLibs { useLegacyPackaging = true } }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.hls)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    // Coil 3 has no HTTP loader by default: without this every online cover/artist photo silently failed.
    implementation(libs.coil.network)
    implementation(libs.libvlc)
    implementation(libs.palette)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)
    // Installs the Compose/AndroidX baseline profiles ahead-of-time → far less first-frame and scroll jank.
    implementation(libs.profileinstaller)
    // libVLC pulls an old Fragment transitively; pin a modern one (Activity Result API requirement).
    implementation(libs.fragment)
    implementation(libs.security.crypto)
}
