import hwhh.codegen.GenerateHookRegistry
import hwhh.codegen.RegistryTarget
import hwhh.codegen.hookRegistryCodegen
import hwhh.codegen.registerHookRegistry
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

val codegenClasspath = hookRegistryCodegen(libs.kotlin.compiler.embeddable, libs.kotlinpoet)

androidComponents {
    onVariants { variant ->
        val task = registerHookRegistry(RegistryTarget.SHARED, variant.name, codegenClasspath)
        variant.sources.kotlin?.addGeneratedSourceDirectory(task, GenerateHookRegistry::outputDir)
    }
}

android {
    namespace = "moe.evil.hwhh.shared"
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
    api(libs.yukihookapi)
    api(libs.kotlinx.serialization.json)
}
