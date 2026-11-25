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
        versionCode = 19
        versionName = "5.1"

        // ONNX Runtime embarque du code natif : uniquement les téléphones ARM 64 bits (tous les récents),
        // pour ne pas alourdir l'APK de 3 autres architectures.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Modèle NudeNet : stocké sans compression dans l'APK
    androidResources {
        noCompress += "onnx"
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

// Deux dépendances externes : ONNX Runtime, pour le modèle de détection NudeNet (bouton Censure,
// portage de github.com/legnome/legnome), et jsoup, pour lire le HTML des pages (recherche générique
// de vidéos). Tout le reste = API Android du framework.
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.32.0")
    // Lecture du HTML par sélecteurs CSS (recherche de vidéos sur n'importe quelle page)
    implementation("org.jsoup:jsoup:1.24.1")
}
