plugins {
    id("com.android.application")
}

android {
    namespace = "app.morphe.gmscompat"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.morphe.gmscompat"
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Sideload build: sign with the debug key unless a release key is configured.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // MicroGContract is shared with the microG extension injected into patched apps.
    sourceSets["main"].java.srcDir("../../shared/src/main/java")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Material 3 (Material You): dynamic color, large top app bar, MaterialSwitch, cards, chips.
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.activity:activity:1.10.1")
}
