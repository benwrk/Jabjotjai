plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The app's semantic version. Android refuses to install a lower versionCode over a higher one
// (developer.android.com/studio/publish/versioning), so versionCode is derived from the version
// rather than maintained by hand, and grows whenever the version does.
val appVersion = "0.5.0"
val appVersionCode = appVersion.split('.').map(String::toInt).let { (major, minor, patch) ->
    require(minor < 1000 && patch < 1000) { "minor and patch must stay below 1000: $appVersion" }
    major * 1_000_000 + minor * 1_000 + patch
}

// Every build, debug and release, is signed with the one stable key, so any build installs over
// any other. Android accepts an update only if it is signed with the installed app's certificate
// or carries a valid proof-of-rotation; otherwise the app must first be uninstalled, "which erases
// all app data from the device" (developer.android.com/google/play/app-updates, read 2026-11-27),
// every recording included. The keystore and its passwords come from Gradle properties set
// outside the repository, and the build stops if any is missing rather than falling back to the
// debug key of whichever machine it runs on. Decision 0010.
val signingProperties = listOf(
    "jabjotjaiSigningStoreFile",
    "jabjotjaiSigningStorePassword",
    "jabjotjaiSigningKeyAlias",
    "jabjotjaiSigningKeyPassword",
).associateWith { providers.gradleProperty(it).orNull.orEmpty() }
val missingSigningProperties = signingProperties.filterValues(String::isEmpty).keys
if (missingSigningProperties.isNotEmpty()) {
    throw GradleException(
        "Signing is not configured: set ${missingSigningProperties.joinToString()} in " +
            "gradle.properties in the Gradle user home (~/.gradle unless GRADLE_USER_HOME is set), " +
            "naming the project's keystore (decision 0010). An APK signed with any other key " +
            "cannot be installed over the app without uninstalling it, which deletes its recordings."
    )
}
val signingStoreFile = file(signingProperties.getValue("jabjotjaiSigningStoreFile"))
if (!signingStoreFile.isFile) {
    throw GradleException("jabjotjaiSigningStoreFile names $signingStoreFile, which is not a file.")
}

android {
    namespace = "com.benwrk.jabjotjai"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.benwrk.jabjotjai"
        minSdk = 33
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("stable") {
            storeFile = signingStoreFile
            storePassword = signingProperties.getValue("jabjotjaiSigningStorePassword")
            keyAlias = signingProperties.getValue("jabjotjaiSigningKeyAlias")
            keyPassword = signingProperties.getValue("jabjotjaiSigningKeyPassword")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            signingConfig = signingConfigs.getByName("stable")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        // The Polar BLE SDK is compiled for Java 21, and Kotlin cannot inline its functions into
        // bytecode that targets an older JVM.
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.polar.ble.sdk)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}