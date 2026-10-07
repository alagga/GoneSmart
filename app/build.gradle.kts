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
        versionCode = 47
        versionName = "0.4.1"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "LASTFM_API_KEY",
            "\"$lastFmApiKey\""
        )
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.androidx.room.runtime)
    testImplementation(libs.junit)
}
