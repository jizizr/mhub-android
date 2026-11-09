plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.mhub.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.mhub.app"
        minSdk = 33
        targetSdk = 37
        // CI passes the tag version and run number; local builds stay 0.1.0 (1).
        versionCode = providers.environmentVariable("MHUB_VERSION_CODE").orNull?.toInt() ?: 1
        versionName = providers.environmentVariable("MHUB_VERSION_NAME").orNull ?: "0.1.0"
    }
    // Release signing comes only from the environment (CI secrets); the keystore never enters the repo.
    val keystore = providers.environmentVariable("MHUB_KEYSTORE_FILE").orNull
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = providers.environmentVariable("MHUB_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("MHUB_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("MHUB_KEY_PASSWORD").get()
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildTypes {
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.glasmio.scaffold)
    implementation(libs.glasmio.preference)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    // QR code of the song link on shared lyric cards.
    implementation(libs.zxing.core)
    // Drag-to-reorder in the play queue (already used by Glasmio).
    implementation("sh.calvin.reorderable:reorderable:3.1.0")
    implementation(libs.palette)
    debugImplementation(libs.compose.tooling)
}
