import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties =
    Properties().apply {
        val localPropertiesFile =
            rootProject.file("local.properties")

        if (localPropertiesFile.exists()) {
            localPropertiesFile
                .inputStream()
                .use { load(it) }
        }
    }

val keystoreProperties =
    Properties().apply {
        val keystorePropertiesFile =
            rootProject.file("keystore.properties")

        if (keystorePropertiesFile.exists()) {
            keystorePropertiesFile
                .inputStream()
                .use { load(it) }
        }
    }

val lastFmApiKey =
    (System.getenv("LASTFM_API_KEY")
        ?: localProperties.getProperty("LASTFM_API_KEY", ""))
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

val releaseStoreFilePath =
    System.getenv("GONESMART_KEYSTORE_FILE")
        ?: keystoreProperties.getProperty("storeFile")

val releaseStorePassword =
    System.getenv("GONESMART_KEYSTORE_PASSWORD")
        ?: keystoreProperties.getProperty("storePassword")

val releaseKeyAlias =
    System.getenv("GONESMART_KEY_ALIAS")
        ?: keystoreProperties.getProperty("keyAlias")

val releaseKeyPassword =
    System.getenv("GONESMART_KEY_PASSWORD")
        ?: keystoreProperties.getProperty("keyPassword")

val releaseSigningConfigured =
    !releaseStoreFilePath.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "io.github.alagga.gonesmart"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.alagga.gonesmart"
        minSdk = 26
        targetSdk = 37
        versionCode = 46
        versionName = "0.4.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "LASTFM_API_KEY",
            "\"$lastFmApiKey\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }

            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility =
            JavaVersion.VERSION_11

        targetCompatibility =
            JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    implementation(
        "io.github.libxposed:service:102.0.0"
    )

    testImplementation(libs.junit)

    androidTestImplementation(
        libs.androidx.espresso.core
    )

    androidTestImplementation(
        libs.androidx.junit
    )

    compileOnly(
        "io.github.libxposed:api:102.0.0"
    )
}
