plugins {
    id("com.android.application")
}

android {
    namespace = "fr.perso.redgifsdl"
    compileSdk = 37

    // Clé fixe versionnée dans le dépôt : chaque nouvelle version s'installe par-dessus
    // l'ancienne (sinon Android exige une désinstallation, qui effacerait la bibliothèque privée).
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        // Ne pas changer applicationId : sinon Android la verrait comme une autre appli.
        applicationId = "fr.perso.redgifsdl"
        minSdk = 26
        targetSdk = 37
        versionCode = 8
        versionName = "3.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Aucune dépendance externe : HttpURLConnection + threads, API Android du framework uniquement.
