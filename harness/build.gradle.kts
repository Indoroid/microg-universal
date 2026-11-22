plugins {
    kotlin("jvm") version "2.4.21"
    application
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation("app.morphe:morphe-patcher:2.5.0")
    implementation(files(System.getenv("MORPHE_PATCHES_LIBRARY_JAR")))
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("com.github.MorpheApp.smali:smali-dexlib2:5eaff5a2c9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
application { mainClass.set("HarnessKt") }
