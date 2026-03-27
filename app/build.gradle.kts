import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

val keystoreProperties = Properties()
val keystoreFile = rootProject.file("keystore.properties")
if (keystoreFile.exists()) keystoreProperties.load(keystoreFile.inputStream())

fun prop(envKey: String, fileKey: String, default: String = ""): String =
    System.getenv(envKey)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(fileKey, default)

android {
    namespace  = "com.akdevelopers.streamnode"
    compileSdk = 35

    defaultConfig {
        applicationId  = "com.akdevelopers.streamnode"
        minSdk         = 26
        targetSdk      = 35
        versionCode    = 12
        versionName    = "2.0.0"

        buildConfigField("String", "FIREBASE_DB_URL",
            "\"https://streamnode-df815-default-rtdb.asia-southeast1.firebasedatabase.app\"")
    }

    signingConfigs {
        create("release") {
            storeFile   = prop("KEYSTORE_PATH",   "storeFile").let { p ->
                if (p.isNotEmpty()) file(p) else null
            }
            storePassword = prop("KEYSTORE_PASS", "storePassword")
            keyAlias      = prop("KEY_ALIAS",     "keyAlias")
            keyPassword   = prop("KEY_PASS",      "keyPassword")
        }
    }

    buildTypes {
        debug {
            versionNameSuffix   = "-debug"
            isDebuggable        = true
            isMinifyEnabled     = false
        }
        release {
            isMinifyEnabled    = true
            isShrinkResources  = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigningConfig = signingConfigs.getByName("release")
            if (releaseSigningConfig.storeFile != null) {
                signingConfig = releaseSigningConfig
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }

    buildFeatures { buildConfig = true }

    lint {
        abortOnError     = false
        warningsAsErrors = false
        checkDependencies = true
        disable += setOf("MissingPermission", "Deprecated", "WrongConstant")
        htmlReport = true
        htmlOutput = file("build/reports/lint/lint-report.html")
        xmlReport  = true
        xmlOutput  = file("build/reports/lint/lint-report.xml")
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md"
            )
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    implementation(project(":core:common"))
    implementation(project(":core:platform"))
    implementation(project(":core:observability"))
    implementation(project(":core:deviceadmin"))
    implementation(project(":domain:streaming"))
    implementation(project(":data:streaming"))
    implementation(project(":feature:setup"))
    implementation(project(":feature:stream"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.activity.ktx)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.database.ktx)

    implementation(libs.okhttp)
}
