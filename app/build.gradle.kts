import java.util.Properties

// Derive versionName and versionCode from the latest git tag (e.g. "v1.2.3").
// Falls back to hardcoded defaults when no tag is reachable.
fun gitTag(projectDir: java.io.File): String? = try {
    val proc = ProcessBuilder("git", "describe", "--tags", "--abbrev=0")
        .directory(projectDir)
        .redirectErrorStream(true)
        .start()
    val line = proc.inputStream.bufferedReader().readLine()?.trim()
    if (proc.waitFor() == 0 && !line.isNullOrEmpty()) line else null
} catch (_: Exception) { null }

val resolvedTag: String? = gitTag(rootProject.projectDir)
val appVersionName: String = resolvedTag?.removePrefix("v") ?: "1.0.1"
val appVersionCode: Int = resolvedTag?.removePrefix("v")
    ?.split(".")
    ?.mapNotNull { it.toIntOrNull() }
    ?.takeIf { it.size >= 2 }
    ?.let { p -> p[0] * 10000 + p[1] * 100 + (p.getOrElse(2) { 0 }) }
    ?: 5

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.healthcare.app"
    compileSdk = 36

    // Read local.properties
    val localProps = Properties()
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { localProps.load(it) }
    }

    defaultConfig {
        applicationId = "jp.co.rssh_jp.healthcareap"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val mapsApiKey = localProps.getProperty("MAPS_API_KEY", "")
        val escapedMapsApiKey = mapsApiKey.replace("\\", "\\\\").replace("\"", "\\\"")
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        buildConfigField("String", "MAPS_API_KEY", "\"$escapedMapsApiKey\"")
    }

    signingConfigs {
        create("release") {
            val keystorePath = localProps.getProperty("KEYSTORE_PATH", "")
            if (keystorePath.isNotEmpty()) {
                storeFile = file(keystorePath)
                storePassword = localProps.getProperty("KEYSTORE_PASSWORD", "")
                keyAlias = localProps.getProperty("KEY_ALIAS", "")
                keyPassword = localProps.getProperty("KEY_PASSWORD", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
}

dependencies {
    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // Core
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Google Maps
    implementation(libs.maps.compose)
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.firestore.ktx)

    // Google Sign-In
    implementation(libs.play.services.auth)

    // WorkManager + Hilt Extensions
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.extensions.compiler)
}
