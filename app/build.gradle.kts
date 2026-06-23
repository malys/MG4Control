plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mg4.control"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mg4.control"
        minSdk = 28
        targetSdk = 34
        versionCode = 14
        versionName = "2.6.2"
    }

    // Signature avec la clé plateforme de la ROM (requise par sharedUserId=android.uid.system).
    // Secrets lus depuis l'environnement (CI) ou gradle.properties local — JAMAIS commités.
    val keystorePath = System.getenv("MG4_KEYSTORE") ?: (project.findProperty("mg4.keystore") as String?)
    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("platform") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("MG4_KEYSTORE_PASSWORD") ?: (project.findProperty("mg4.keystore.password") as String?)
                keyAlias = System.getenv("MG4_KEY_ALIAS") ?: (project.findProperty("mg4.key.alias") as String?) ?: "platform"
                keyPassword = System.getenv("MG4_KEY_PASSWORD") ?: (project.findProperty("mg4.key.password") as String?)
            }
        }
    }

    flavorDimensions += "dist"
    productFlavors {
        create("online") {
            dimension = "dist"
            buildConfigField("boolean", "OFFLINE", "false")
        }
        create("offline") {
            dimension = "dist"
            applicationIdSuffix = ".offline"
            versionNameSuffix = "-offline"
            buildConfigField("boolean", "OFFLINE", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("platform")?.let { signingConfig = it }
        }
        debug {
            signingConfigs.findByName("platform")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            if (variant.buildType.name == "release") {
                output.outputFileName = "MG4Control-${variant.flavorName}-${variant.versionName}.apk"
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.gson)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.viewpager2)

    // QR code (génération dans le dialog Infos) — flavor online uniquement.
    // Le flavor offline n'embarque PAS ZXing (réduction de surface, pas de dépendance superflue).
    "onlineImplementation"("com.google.zxing:core:3.5.3")
}
