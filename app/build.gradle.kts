plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val keystorePath: String? = System.getenv("KEYSTORE_FILE")
val keystorePass: String? = System.getenv("KEYSTORE_PASSWORD")
val haveReleaseKey = keystorePath != null && keystorePass != null && File(keystorePath).exists()

android {
    namespace = "com.alok.hello"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alok.hello"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "0.1.$runNumber"
    }

    signingConfigs {
        if (haveReleaseKey) {
            create("hello") {
                storeFile = File(keystorePath!!)
                storePassword = keystorePass
                keyAlias = "hello"
                keyPassword = keystorePass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (haveReleaseKey) signingConfigs.getByName("hello") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
