plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.layartrans"
    compileSdk = 34

    defaultConfig {
        applicationId = "id.layartrans"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.0")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-japanese:16.0.0")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-korean:16.0.0")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.0")
    implementation("com.google.mlkit:translate:17.0.3")
}
