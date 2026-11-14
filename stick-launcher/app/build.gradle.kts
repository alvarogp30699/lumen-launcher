plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// El flujo de GitHub pasa -PversionCode=<nº de ejecución>; en local vale 1.
val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.alvaro.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.alvaro.launcher"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = "0.2." + appVersionCode
    }

    // Clave fija: así las actualizaciones se instalan encima sin desinstalar.
    signingConfigs {
        create("launcher") {
            storeFile = file("launcher.keystore")
            storePassword = "android"
            keyAlias = "launcher"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // Sin modo depuración: la app va bastante más rápida en el stick
            isDebuggable = false
            signingConfig = signingConfigs.getByName("launcher")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("launcher")
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

dependencies {
    implementation("androidx.core:core:1.12.0")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("com.google.zxing:core:3.5.3")
    // Lectura del reloj y el marcador de la retransmisión (sin internet, modelo incluido)
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
