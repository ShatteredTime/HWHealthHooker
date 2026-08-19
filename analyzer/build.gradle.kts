plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "moe.evil.hwhh.analyzer"
    compileSdk = 37

    defaultConfig {
        minSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
            )
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.jadx.core)
    api(libs.jadx.dex.input)
    implementation(libs.logback.android)
    implementation(libs.kotlinx.serialization.json)
}
