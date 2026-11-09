// Platform-independent client logic shared in behaviour with iOS MHubKit:
// models, API client, playback readiness, play queue and lyrics.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(libs.serialization.json)
    api(libs.coroutines.core)
    api(libs.okhttp)
    testImplementation(libs.junit)
}

tasks.test {
    // Fixtures and queue cases live in contract/ (mirrored in mhub-ios).
    systemProperty("mhub.contract", rootProject.file("contract").absolutePath)
}
