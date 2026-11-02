import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun loadProperties(file: File) = Properties().apply { if (file.exists()) file.inputStream().use(::load) }

// The signing key stays outside the source; local.properties points at it.
val local = loadProperties(rootProject.file("local.properties"))
val signing = local.getProperty("signing.file")?.let { loadProperties(file(it)) }

android {
    namespace = "com.sayang.translate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sayang.translate"
        minSdk = 30
        targetSdk = 36
        versionCode = 13
        versionName = "10.1"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
