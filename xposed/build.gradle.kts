import hwhh.codegen.GenerateHookRegistry
import hwhh.codegen.RegistryTarget
import hwhh.codegen.hookRegistryCodegen
import hwhh.codegen.registerHookRegistry
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.serialization)
}

val codegenClasspath = hookRegistryCodegen(libs.kotlin.compiler.embeddable, libs.kotlinpoet)

androidComponents {
    onVariants { variant ->
        val task = registerHookRegistry(RegistryTarget.XPOSED, variant.name, codegenClasspath)
        variant.sources.kotlin?.addGeneratedSourceDirectory(task, GenerateHookRegistry::outputDir)
    }
}

android {
    namespace = "moe.evil.hwhh.xposed"
    compileSdk = 37

    defaultConfig {
        minSdk = 36
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
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
        freeCompilerArgs.addAll(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions"
        )
    }
}

dependencies {
    implementation(project(":shared"))
    api(project(":kdxref"))
    implementation(libs.androidx.core.ktx)
    compileOnly(libs.rovo89.xposed.api)
    compileOnly(project(":xbinterface"))
    ksp(libs.yukihookapi.ksp.xposed)
    api(libs.yukihookapi)
    implementation(libs.garmin.fit)
    implementation(libs.kotlinx.serialization.json)
}
