plugins {
  alias(libs.plugins.android.application)
}

/*
 * The version and the signing key come from the environment when there is one, so that a
 * release built by CI is stamped with its tag and signed with the project's key, and a
 * build on a laptop needs neither. `providers.environmentVariable` rather than
 * `System.getenv` so that the configuration cache knows what the build read.
 */
fun env(name: String): String? = providers.environmentVariable(name).orNull

val keystorePath = env("KEYSTORE_FILE")

android {
    namespace = "fr.steren.attractors"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.steren.attractors"
        minSdk = 26
        targetSdk = 36
        versionCode = env("VERSION_CODE")?.toInt() ?: 1
        versionName = env("VERSION_NAME") ?: "1.0"
    }

    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = env("KEYSTORE_PASSWORD")
                keyAlias = env("KEY_ALIAS")
                keyPassword = env("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Without a key the build still runs, and produces an APK that says "unsigned"
            // in its name rather than one that looks installable and is not.
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else null
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = false
        aidl = false
        buildConfig = false
        shaders = false
        resValues = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.preference)
}
