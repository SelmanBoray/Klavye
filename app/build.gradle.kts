plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release anahtarı yazarın ~/.gradle/gradle.properties dosyasında tanımlıdır; başkalarında yoktur.
val storeFilePath = providers.gradleProperty("KLAVYE_STORE_FILE").orNull
val storePass = providers.gradleProperty("KLAVYE_STORE_PASSWORD").orNull
val keyAliasName = providers.gradleProperty("KLAVYE_KEY_ALIAS").orNull
val keyPass = providers.gradleProperty("KLAVYE_KEY_PASSWORD").orNull
val hasReleaseKey = !storeFilePath.isNullOrBlank() && !storePass.isNullOrBlank() &&
    !keyAliasName.isNullOrBlank() && !keyPass.isNullOrBlank() && file(storeFilePath).exists()

android {
    namespace = "com.selman.klavye"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.selman.klavye"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(storeFilePath!!)
                storePassword = storePass
                keyAlias = keyAliasName
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                logger.lifecycle("UYARI: Release anahtarı tanımlı olmadığı için debug anahtarıyla imzalanıyor; ayrıntı için README'ye bakın.")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
