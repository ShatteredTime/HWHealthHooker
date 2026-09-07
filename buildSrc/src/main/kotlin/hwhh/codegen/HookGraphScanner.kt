package hwhh.codegen

import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import java.io.File

internal data class HookerNode(
    val name: String,
    val pkg: String,
    val superType: String,
    val rootOrder: Int?,
    val deps: Set<String>,
    val toggles: Set<String>,
    val origin: String,
)

internal data class HookGraph(
    val roots: List<HookerNode>,
    val nodes: Map<String, HookerNode>,
    val prefs: List<String>,
) {
    fun featureOf(node: HookerNode) =
        CAMEL_HUMP.replace(node.name.removeSuffix(HOOKER_SUFFIX), "$1_$2").uppercase()

    fun gates(): Map<String, List<String>> {
        val gatedBy = roots.flatMap { root ->
            val feature = featureOf(root)
            val seen = HashSet<String>()
            val pending = ArrayDeque(listOf(root.name))
            val toggles = LinkedHashSet<String>()
            while (pending.isNotEmpty()) {
                val node = nodes.getValue(pending.removeFirst())
                if (seen.add(node.name)) {
                    toggles += node.toggles
                    pending += node.deps
                }
            }
            toggles.map { it to feature }
        }.groupBy({ it.first }, { it.second })
        return prefs.associateWith { gatedBy[it].orEmpty() }
    }
}

private const val HOOKER_SUFFIX = "Hooker"
private const val ROOT_SUPER_TYPE = HookNames.BASE_HOOKER
private const val REQUIRE = "require"
private const val IF_DEBUG_PREF = "ifDebugPref"
private const val DEBUG_TOGGLE = HookNames.DEBUG_TOGGLE
private const val PREFS_DATA = HookNames.PREFS_DATA
private const val DEBUG_PREFS_OBJECT = HookNames.DEBUG_PREFS
private const val HOOK_ROOT_ANNOTATION = "HookRoot"

private val CAMEL_HUMP = Regex("([a-z0-9])([A-Z])")
private val SEED_BASES = setOf(HookNames.SEED_HOOKER, ROOT_SUPER_TYPE)
private val GUARDED_IMPORT_PREFIXES = listOf("com.highcapable.kavaref.", "org.luckypray.dexkit.")
private val WRAPPER_DIR = "/" + HookNames.WRAPPER_PACKAGE.replace('.', '/')
private val RESOLUTION_POLICY_CALLS = setOf("orFail", "optionally")

internal class HookGraphScanner(
    private val hookerSources: List<File>,
    private val prefsSource: File,
) {
    private val failures = mutableListOf<String>()

    @OptIn(CompilerConfiguration.Internals::class, K1Deprecation::class)
    private fun <R> withKtFiles(block: ((File) -> KtFile) -> R): R {
        val debugName = "hook-registry-codegen"
        val disposable = Disposer.newDisposable(debugName)
        return AutoCloseable(disposable::dispose).use {
            val factory = KtPsiFactory(
                KotlinCoreEnvironment.createForProduction(
                    disposable,
                    CompilerConfiguration().apply {
                        put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
                        put(CommonConfigurationKeys.MODULE_NAME, debugName)
                    },
                    EnvironmentConfigFiles.JVM_CONFIG_FILES,
                ).project,
                markGenerated = true,
            )
            block { file -> factory.createFile(file.name, file.readText().replace("\r\n", "\n")) }
        }
    }

    fun scan() = withKtFiles { parse ->
        val hookerFiles = hookerSources.map(parse)
        enforceWrapperBoundary(hookerSources.zip(hookerFiles))
        val bases = resolveBases(hookerFiles)
        val nodes = hookerFiles.flatMap { file -> parseHookers(file, bases) }
            .associateBy(HookerNode::name)
        val prefs = parseDebugPrefs(parse(prefsSource))
        validate(nodes, prefs)
        check(failures.isEmpty()) {
            failures.joinToString(
                separator = "\n",
                prefix = "Hook registry generation refused to guess:\n",
            ) { "  - $it" }
        }
        HookGraph(
            roots = nodes.values.filter { it.rootOrder != null }.sortedBy(HookerNode::rootOrder),
            nodes = nodes,
            prefs = prefs,
        )
    }

    private fun enforceWrapperBoundary(files: List<Pair<File, KtFile>>) {
        val violations = files.flatMap { (source, file) ->
            val inWrapperDir = source.parentFile.invariantSeparatorsPath.endsWith(WRAPPER_DIR)
            val inWrapperPackage = file.packageFqName.asString() == HookNames.WRAPPER_PACKAGE
            if (inWrapperDir && inWrapperPackage) return@flatMap emptyList()
            if (inWrapperDir || inWrapperPackage) return@flatMap listOf(
                "${file.name}: package ${file.packageFqName} and directory disagree about " +
                        "being the wrapper"
            )
            val imports = file.importDirectives.filter { directive ->
                directive.importedFqName?.asString()
                    ?.let { fq -> GUARDED_IMPORT_PREFIXES.any(fq::startsWith) } == true
            }
            val optIns = file.collectDescendantsOfType<KtNameReferenceExpression> {
                it.getReferencedName() == HookNames.HOST_INTERNAL_API
            }
            val strayPolicy = file.collectDescendantsOfType<KtCallExpression> { call ->
                call.calleeExpression?.text in RESOLUTION_POLICY_CALLS &&
                        generateSequence(call.parent) { it.parent }.none {
                            it is KtNamedFunction && it.name == HookNames.ON_HOOK_WITH_DEXKIT
                        }
            }
            (imports + optIns + strayPolicy).map {
                val line = file.text.take(it.textOffset).count { ch -> ch == '\n' } + 1
                "${file.name}:$line: ${it.text.lineSequence().first()}"
            }
        }
        check(violations.isEmpty()) {
            violations.joinToString(
                separator = "\n",
                prefix = "Hooker sources must go through ${HookNames.WRAPPER_PACKAGE}: no raw " +
                        "KavaRef/DexKit import, no ${HookNames.HOST_INTERNAL_API} opt-in, and " +
                        "${RESOLUTION_POLICY_CALLS.joinToString("/")} only inside " +
                        "${HookNames.ON_HOOK_WITH_DEXKIT}:\n",
            ) { "  - $it" }
        }
    }

    private fun resolveBases(files: List<KtFile>): Set<String> {
        val subclasses = files.flatMap { it.collectDescendantsOfType<KtClass>() }
            .filter { it.hasModifier(KtTokens.ABSTRACT_KEYWORD) }
            .mapNotNull { clazz ->
                clazz.name?.let { name -> clazz.superTypeName()?.let { it to name } }
            }
            .groupBy({ it.first }, { it.second })
        return buildSet {
            val pending = ArrayDeque(SEED_BASES)
            while (pending.isNotEmpty()) {
                val base = pending.removeFirst()
                if (add(base)) pending += subclasses[base].orEmpty()
            }
        }
    }

    private fun parseHookers(file: KtFile, bases: Set<String>) =
        file.declarations.filterIsInstance<KtObjectDeclaration>().mapNotNull { obj ->
            val name = obj.name ?: return@mapNotNull null
            val order = obj.annotationEntries.firstOrNull {
                (it.typeReference?.typeElement as? KtUserType)?.referencedName ==
                        HOOK_ROOT_ANNOTATION
            }?.valueArguments?.firstOrNull()?.getArgumentExpression()?.text?.toIntOrNull()
            val superType = obj.superTypeName()
            if (superType == null || superType !in bases) {
                if (order != null) {
                    failures += "${file.name}: @$HOOK_ROOT_ANNOTATION on $name, which extends no " +
                            "hooker base"
                }
                return@mapNotNull null
            }
            HookerNode(
                name = name,
                pkg = file.packageFqName.asString(),
                superType = superType,
                rootOrder = order,
                deps = parseDeps(file, obj, name),
                toggles = parseToggles(file, obj, name),
                origin = file.name,
            )
        }

    private fun parseDeps(file: KtFile, obj: KtObjectDeclaration, owner: String): Set<String> {
        val delegated = obj.collectDescendantsOfType<KtProperty>()
            .mapNotNullTo(HashSet()) { it.delegateExpression as? KtCallExpression }
        return obj.callsTo(REQUIRE).mapNotNullTo(LinkedHashSet()) { call ->
            val arg = call.valueArguments.singleOrNull()?.getArgumentExpression()
            when {
                call in delegated -> ((arg as? KtLambdaExpression)?.bodyExpression
                    ?.statements?.singleOrNull() as? KtNameReferenceExpression)?.getReferencedName()
                    ?: run {
                        failures += "${file.name}: $owner declares a `by $REQUIRE { }` whose body " +
                                "is not a single hooker reference"
                        null
                    }

                arg is KtLambdaExpression || arg is KtCallableReferenceExpression -> {
                    failures += "${file.name}: $owner calls $REQUIRE() outside a property " +
                            "delegate; write `by $REQUIRE { SomeHooker }` so the edge stays " +
                            "visible to codegen"
                    null
                }

                else -> null
            }
        }
    }

    private fun parseToggles(file: KtFile, obj: KtObjectDeclaration, owner: String) =
        obj.callsTo(IF_DEBUG_PREF).mapNotNullTo(LinkedHashSet()) { call ->
            (call.valueArguments.filterNot { it is KtLambdaArgument }
                .singleOrNull()?.getArgumentExpression() as? KtDotQualifiedExpression)
                ?.takeIf { it.receiverExpression.text == DEBUG_TOGGLE }
                ?.selectorExpression?.text
                ?: run {
                    failures += "${file.name}: $owner calls $IF_DEBUG_PREF with something other " +
                            "than a plain $DEBUG_TOGGLE constant"
                    null
                }
        }

    private fun parseDebugPrefs(file: KtFile): List<String> {
        val obj = file.collectDescendantsOfType<KtObjectDeclaration>()
            .singleOrNull { it.name == DEBUG_PREFS_OBJECT }
            ?: run {
                failures += "${file.name}: expected exactly one $DEBUG_PREFS_OBJECT object to " +
                        "derive $DEBUG_TOGGLE from"
                return emptyList()
            }
        return obj.declarations.filterIsInstance<KtProperty>().mapNotNull { property ->
            val call = property.initializer as? KtCallExpression ?: return@mapNotNull null
            property.name?.takeIf {
                call.calleeExpression?.text == PREFS_DATA && call.valueArguments.any { arg ->
                    (arg.getArgumentExpression() as? KtConstantExpression)?.node?.elementType ==
                            KtNodeTypes.BOOLEAN_CONSTANT
                }
            }
        }
    }

    private fun validate(nodes: Map<String, HookerNode>, prefs: List<String>) {
        val prefNames = prefs.toHashSet()
        nodes.values.forEach { node ->
            if (node.superType == ROOT_SUPER_TYPE && node.rootOrder == null) {
                failures += "${node.origin}: ${node.name} extends $ROOT_SUPER_TYPE but has no " +
                        "@$HOOK_ROOT_ANNOTATION(order = ...), so it cannot appear in HookFeature"
            }
            if (node.superType != ROOT_SUPER_TYPE && node.rootOrder != null) {
                failures += "${node.origin}: ${node.name} is annotated @$HOOK_ROOT_ANNOTATION but " +
                        "is not a $ROOT_SUPER_TYPE, so config could never switch it"
            }
            node.deps.filterNot(nodes::containsKey).forEach {
                failures += "${node.origin}: ${node.name} requires $it, which is not a hooker object"
            }
            node.toggles.filterNot(prefNames::contains).forEach {
                failures += "${node.origin}: ${node.name} gates on $DEBUG_TOGGLE.$it, which has no " +
                        "matching $PREFS_DATA in $DEBUG_PREFS_OBJECT"
            }
        }
        nodes.values.mapNotNull { node -> node.rootOrder?.let { it to node.name } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
            .forEach { (order, clash) ->
                failures += "Duplicate @$HOOK_ROOT_ANNOTATION(order = $order) on " +
                        clash.joinToString()
            }
    }
}

private fun KtClassOrObject.superTypeName() = superTypeListEntries
    .filterIsInstance<KtSuperTypeCallEntry>()
    .firstOrNull()?.typeAsUserType?.referencedName

private fun KtObjectDeclaration.callsTo(callee: String) =
    collectDescendantsOfType<KtCallExpression> { it.calleeExpression?.text == callee }
