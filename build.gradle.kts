// Module Android partagé (sous-module git) : avis de mise à jour depuis le site public.
// Le projet racine doit déclarer le plugin com.android.library (même version d'AGP que l'appli).
plugins {
    id("com.android.library")
}

android {
    namespace = "com.spdyn1.maj"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.19.1")   // FileProvider
}
