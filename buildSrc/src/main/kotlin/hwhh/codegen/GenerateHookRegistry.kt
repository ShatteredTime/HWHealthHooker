package hwhh.codegen

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

enum class RegistryTarget(internal val pkg: String) {
    SHARED("moe.evil.hwhh.shared"),
    XPOSED("moe.evil.hwhh.xposed"),
}

interface HookRegistryParameters : WorkParameters {
    val hookerSources: ConfigurableFileCollection
    val prefsSource: RegularFileProperty
    val target: Property<RegistryTarget>
    val outputDir: DirectoryProperty
}

abstract class GenerateHookRegistryWork : WorkAction<HookRegistryParameters> {
    override fun execute() {
        val graph = HookGraphScanner(
            parameters.hookerSources.files.sortedBy { it.path },
            parameters.prefsSource.get().asFile,
        ).scan()
        val root = parameters.outputDir.get().asFile
        check(root.deleteRecursively()) { "Cannot clear the registry output at $root" }
        root.mkdirs()
        when (parameters.target.get()) {
            RegistryTarget.SHARED -> graph.sharedFile()
            RegistryTarget.XPOSED -> graph.xposedFile()
        }.writeTo(root)
    }
}

@CacheableTask
abstract class GenerateHookRegistry : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val hookerSources: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val prefsSource: RegularFileProperty

    @get:Input
    abstract val target: Property<RegistryTarget>

    @get:Classpath
    abstract val codegenClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val workers: WorkerExecutor

    @TaskAction
    fun generate() {
        workers.classLoaderIsolation { classpath.from(codegenClasspath) }
            .submit(GenerateHookRegistryWork::class.java) {
                hookerSources.from(this@GenerateHookRegistry.hookerSources)
                prefsSource.set(this@GenerateHookRegistry.prefsSource)
                target.set(this@GenerateHookRegistry.target)
                outputDir.set(this@GenerateHookRegistry.outputDir)
            }
    }
}

fun Project.hookRegistryCodegen(
    vararg tools: Provider<MinimalExternalModuleDependency>,
): FileCollection {
    val scope = configurations.dependencyScope("hookRegistryCodegen")
    tools.forEach { dependencies.addProvider(scope.name, it) }
    return configurations.resolvable("hookRegistryCodegenClasspath") {
        extendsFrom(scope.get())
    }.get()
}

fun Project.registerHookRegistry(
    target: RegistryTarget,
    variant: String,
    codegen: FileCollection,
): TaskProvider<GenerateHookRegistry> = tasks.register<GenerateHookRegistry>(
    "generate${variant.replaceFirstChar(Char::uppercase)}HookRegistry"
) {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Derives HookFeature/DebugToggle from the xposed hooker graph."
    hookerSources.from(
        layout.settingsDirectory.dir("xposed/src/main/java")
            .asFileTree.matching { include("**/*.kt") }
    )
    prefsSource.set(
        layout.settingsDirectory.file("shared/src/main/java/moe/evil/hwhh/shared/Constants.kt")
    )
    this.target.set(target)
    codegenClasspath.from(codegen)
    outputDir.convention(layout.buildDirectory.dir("generated/hookRegistry/$variant"))
}
