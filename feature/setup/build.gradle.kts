plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace  = "com.akdevelopers.streamnode"
    compileSdk = 35

    defaultConfig { minSdk = 26 }

    buildFeatures { viewBinding = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:observability"))
    implementation(project(":core:deviceadmin"))
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
}
