plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace  = "com.akdevelopers.streamnode.data.streaming"
    compileSdk = 35

    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:observability"))
    implementation(project(":core:platform"))
    implementation(project(":core:deviceadmin"))
    implementation(project(":domain:streaming"))

    implementation(libs.okhttp)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.database.ktx)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // WebRTC peer-to-peer — Google's pre-built AAR (stable, matches Chrome's engine)
    implementation("io.github.webrtc-sdk:android:125.6422.06.1")

    // Feature 9 — Location Streaming: FusedLocationProviderClient
    implementation(libs.play.services.location)

    compileOnly(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
}
