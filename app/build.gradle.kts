plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.brickystudios.antwortradar"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.brickystudios.antwortradar"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.5.1"
    }

    // Two APKs from one code base, same app id and key, so either can be installed over the other:
    // "offline" has no internet permission at all; "updates" may reach GitHub, only to fetch a newer release.
    flavorDimensions += "dist"
    productFlavors {
        create("offline") {
            dimension = "dist"
            buildConfigField("boolean", "SELF_UPDATE", "false")
            buildConfigField("String", "APK_NAME", "\"Unreplied.apk\"")
        }
        create("updates") {
            dimension = "dist"
            buildConfigField("boolean", "SELF_UPDATE", "true")
            buildConfigField("String", "APK_NAME", "\"Unreplied-Updates.apk\"")
        }
    }

    // Release key lives only in CI secrets (and ~/.config/unreplied on the maintainer's machine).
    // Without it, local release builds fall back to the debug key so they still install.
    val storePass = System.getenv("SIGNING_STORE_PASSWORD")
    signingConfigs {
        if (storePass != null) create("release") {
            storeFile = file(System.getenv("SIGNING_STORE_FILE") ?: "release.jks")
            storePassword = storePass
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // no Google dependency info block: F-Droid/GrapheneOS users can check the APK has nothing to hide
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303") // Android's org.json is a stub in local unit tests
}
