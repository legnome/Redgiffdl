plugins {
    id("com.android.application")
}

android {
    namespace = "fr.perso.redgifsdl"
    compileSdk = 37

    defaultConfig {
        applicationId = "fr.perso.redgifsdl"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Aucune dépendance externe : HttpURLConnection + un thread, API Android du framework uniquement.
