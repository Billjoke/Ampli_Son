plugins {
    id("com.android.application")
}

android {
    namespace = "com.ampli.son"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ampli.son"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signé avec la clé de debug pour pouvoir l'installer directement
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}
