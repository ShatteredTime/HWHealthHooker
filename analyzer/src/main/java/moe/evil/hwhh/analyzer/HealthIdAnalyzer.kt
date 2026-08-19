package moe.evil.hwhh.analyzer

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.impl.NoOpCodeCache
import jadx.api.impl.SimpleCodeWriter
import jadx.api.security.JadxSecurityFlag
import jadx.api.security.impl.JadxSecurity
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function
import java.util.zip.ZipFile

private val DEX_ENTRY = Regex("classes\\d*\\.dex")

private const val LOAD_SHARE = 0.35f

private const val STRING_IDS_OFF = 0x3c
private const val TYPE_IDS_OFF = 0x44
private const val CLASS_DEFS_SIZE = 0x60
private const val CLASS_DEFS_OFF = 0x64
private const val CLASS_DEF_ITEM = 32

private fun String.toDescriptor() = "L${replace('.', '/')};"

private fun ByteArray.definedAmong(anchors: Map<String, ByteArray>): Set<String> {
    val buf = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    val strings = buf.getInt(STRING_IDS_OFF)
    val types = buf.getInt(TYPE_IDS_OFF)
    val defsOff = buf.getInt(CLASS_DEFS_OFF)
    val found = HashSet<String>(anchors.size)
    for (index in 0 until buf.getInt(CLASS_DEFS_SIZE)) {
        if (found.size == anchors.size) break
        val typeIdx = buf.getInt(defsOff + index * CLASS_DEF_ITEM)
        var pos = buf.getInt(strings + buf.getInt(types + typeIdx * 4) * 4)
        var len = 0
        var shift = 0
        do {
            val byte = this[pos++].toInt()
            len = len or ((byte and 0x7f) shl shift)
            shift += 7
        } while (byte and 0x80 != 0)
        anchors.forEach { (name, needle) ->
            if (needle.size == len && needle.indices.all { this[pos + it] == needle[it] }) {
                found += name
            }
        }
    }
    return found
}

private fun ByteArray.containsAscii(needle: ByteArray): Boolean {
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
        return true
    }
    return false
}

private class DexUnit(val file: File, val defines: Set<String>, val callsSetter: Boolean)

private class Scratch(root: File) : AutoCloseable {
    private companion object {
        val live = ConcurrentHashMap.newKeySet<String>()
    }

    private val home = File(root, "analysis").apply { mkdirs() }
    private val run = Files.createTempDirectory(home.toPath(), "run").toFile()
        .also { live += it.name }

    val dex = File(run, "dex").apply { mkdirs() }
    val out = File(run, "out")

    init {
        home.listFiles()?.forEach { if (it.name !in live) it.deleteRecursively() }
    }

    override fun close() {
        live -= run.name
        run.deleteRecursively()
    }
}

object HealthIdAnalyzer {
    fun analyze(
        apk: File,
        workDir: File,
        log: (String) -> Unit = {},
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): AnalysisOutcome<AnalyzerReport> = outcomeOf {
        val started = System.currentTimeMillis()
        Scratch(workDir).use { scratch ->
            val optionDescriptor = OPTION_CLASS.toDescriptor()
            val statDescriptor = "com.huawei.hihealthservice.store.stat.HiTrackStat".toDescriptor()
            val anchors = listOf(optionDescriptor, statDescriptor)
                .associateWith { it.toByteArray(Charsets.US_ASCII) }
            val setterNeedle = "setConstantsKey".toByteArray(Charsets.US_ASCII)

            val units = ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .filter { DEX_ENTRY.matches(it.name) }
                    .mapNotNull { entry ->
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        val defines = bytes.definedAmong(anchors)
                        val calls = bytes.containsAscii(setterNeedle)
                        if (!calls && defines.isEmpty()) return@mapNotNull null
                        DexUnit(
                            File(scratch.dex, entry.name).apply { writeBytes(bytes) },
                            defines,
                            calls
                        )
                    }
                    .toList()
            }

            val base = units.firstOrNull { optionDescriptor in it.defines }
                ?: error("$OPTION_CLASS not defined in any dex")
            log("Base ${base.file.name}, dexes ${units.size}")

            val names = Collections.synchronizedSet(LinkedHashSet<IdName>())
            val facts = Collections.synchronizedSet(LinkedHashSet<Fact>())
            val visited = HashSet<String>()
            val unresolved = AtomicInteger()
            var loaded = 0
            var jadxErrors = 0
            val step = 1f / units.size

            units.sortedByDescending { it === base }.forEachIndexed { index, unit ->
                if (isCancelled()) throw AnalysisCancelled()
                onProgress(index * step)
                val inputs = if (unit === base) listOf(base.file) else listOf(base.file, unit.file)
                val jadxArgs = JadxArgs().apply {
                    inputFiles = inputs
                    outDir = scratch.out
                    threadsCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
                    codeCache = NoOpCodeCache.INSTANCE
                    codeWriterProvider = Function(::SimpleCodeWriter)
                    isLoadJadxClsSetFile = false
                    isReplaceConsts = false
                    security = JadxSecurity(
                        JadxSecurityFlag.all().apply { remove(JadxSecurityFlag.SECURE_XML_PARSER) }
                    )
                }
                runCatching {
                    JadxDecompiler(jadxArgs).use { jadx ->
                        jadx.addCustomPass(
                            IdNamePass(names::add, { unresolved.incrementAndGet() }, facts::add),
                        )
                        jadx.load()
                        onProgress((index + LOAD_SHARE) * step)
                        loaded = maxOf(loaded, jadx.classes.size)

                        val targets = LinkedHashSet<String>()
                        jadx.root.resolveClass(OPTION_CLASS)?.let { option ->
                            listOf(SET_KEYS, SET_BOTH)
                                .mapNotNull(option::searchMethodByShortId)
                                .flatMap { it.useIn }
                                .mapTo(targets) { it.parentClass.classInfo.fullName }
                        }
                        jadx.classes.mapNotNullTo(targets) {
                            it.fullName.takeIf { name -> name.startsWith(STAT_PACKAGE) }
                        }
                        targets.removeAll(visited)
                        targets.forEachIndexed { done, name ->
                            if (isCancelled()) throw AnalysisCancelled()
                            runCatching {
                                jadx.searchJavaClassByOrigFullName(name)?.decompile()
                            }.onFailure {
                                log("Aborted at $name [${index + 1}/${units.size}] ${unit.file.name}")
                                throw it
                            }
                            visited += name
                            val share = LOAD_SHARE + (1f - LOAD_SHARE) * (done + 1) / targets.size
                            onProgress((index + share) * step)
                        }
                        jadxErrors += jadx.errorsCount
                        log(
                            "Scanned [${index + 1}/${units.size}] ${unit.file.name} +${targets.size}cls " +
                                    "names=${names.size} jadxErrors=${jadx.errorsCount}"
                        )
                    }
                }.onFailure {
                    if (it !is AnalysisCancelled) {
                        log("Aborted [${index + 1}/${units.size}] ${unit.file.name}")
                    }
                    throw it
                }
            }

            if (isCancelled()) throw AnalysisCancelled()

            facts.filterIsInstance<Fact.SourceType>()
                .groupBy(Fact.SourceType::owner)
                .forEach { (owner, sources) ->
                    val id = sources.mapTo(HashSet(), Fact.SourceType::id).singleOrNull()
                        ?: return@forEach
                    val label =
                        owner.substringAfterLast('.').removePrefix("Hi").removeSuffix("Stat")
                    names += IdName(
                        id,
                        label.replaceFirstChar(Char::lowercaseChar),
                        IdNameSource.CLASS_NAME,
                        owner
                    )
                }

            val slotUnits = facts.filterIsInstance<Fact.Slot>()
                .groupBy(Fact.Slot::index)
                .mapNotNull { (index, slots) ->
                    slots.mapTo(HashSet(), Fact.Slot::unit).singleOrNull()?.let { index to it }
                }
                .toMap()
            val families = facts.filterIsInstance<Fact.Family>().associate { it.field to it.label }
            log("Track units=${slotUnits.size} families=${families.size}")
            onProgress(1f)

            val tags = facts.filterIsInstance<Fact.LogTag>().associate { it.method to it.tag }
            facts.filterIsInstance<Fact.Consumer>()
                .groupBy(Fact.Consumer::method)
                .forEach { (method, edges) ->
                    val id = edges.mapTo(HashSet(), Fact.Consumer::id).singleOrNull()
                        ?: return@forEach
                    tags[method]?.let {
                        names += IdName(id, it, IdNameSource.METHOD_LOG, method)
                    }
                }

            AnalyzerReport(
                apk = apk.path,
                dexes = units.map { it.file.name },
                loadedClasses = loaded,
                visitedClasses = visited.size,
                unresolvedOptions = unresolved.get(),
                jadxErrors = jadxErrors,
                elapsedMs = System.currentTimeMillis() - started,
                names = names.toList(),
                trackUnits = slotUnits,
                trackFamilies = families,
                facts = facts.map(Fact::toString),
            )
        }
    }
}
