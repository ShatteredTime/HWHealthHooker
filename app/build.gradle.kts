plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val gitCommit = runCatching {
    fun git(vararg args: String) = providers.exec {
        workingDir = rootDir
        commandLine("git", *args)
    }.standardOutput.asText.get()
    val hash = git("rev-parse", "HEAD").trim().take(12)
    require(hash.length == 12)
    val dirty = git("--no-optional-locks", "status", "-uno", "--porcelain").isNotBlank()
    if (dirty) "$hash-dirty" else hash
}.getOrNull() ?: "unknown"

android {
    namespace = "moe.evil.hwhh"
    compileSdk = 37

    defaultConfig {
        applicationId = "moe.evil.hwhh"
        minSdk = 36
        versionCode = 8
        versionName = "1.0.8"
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        additionalParameters += listOf("--package-id", "0x64", "--allow-reserved-package-id")
    }

    packaging {
        resources {
            excludes += setOf(
                "clst/**",
                "META-INF/**/LICENSE*",
                "**/*.kotlin_builtins",
                "DebugProbesKt.bin",
            )
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    runtimeOnly(project(":xposed"))
    implementation(project(":analyzer"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
