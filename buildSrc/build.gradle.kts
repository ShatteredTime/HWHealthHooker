plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.kotlin.compiler.embeddable)
    compileOnly(libs.kotlinpoet)
}
