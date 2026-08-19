import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "moe.evil.hwhh.kdxref"
    compileSdk = 37

    defaultConfig {
        minSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    api(project(":shared"))
    api(libs.yukihookapi)
    api(platform(libs.kavaref.bom))
    api(libs.kavaref.core)
    api(libs.kavaref.android)
    api(libs.kavaref.extension)
    api(libs.dexkit)
}
