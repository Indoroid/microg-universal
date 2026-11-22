plugins { id("com.android.application") }
android {
    namespace = "test.gmsclient"
    compileSdk = 35
    defaultConfig { applicationId = "test.gmsclient"; minSdk = 24; targetSdk = 35; versionCode = 1; versionName = "1" }
    buildTypes { release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")); signingConfig = signingConfigs.getByName("debug") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("com.google.android.gms:play-services-base:18.11.1")
    implementation("com.google.firebase:firebase-messaging:26.0.2")
}
