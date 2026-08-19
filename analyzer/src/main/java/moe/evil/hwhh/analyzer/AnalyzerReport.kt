package moe.evil.hwhh.analyzer

import kotlinx.serialization.Serializable

@Serializable
enum class IdNameSource { CONSTANTS_KEY, ADJACENT_TAG, METHOD_LOG, CLASS_NAME }

@Serializable
data class IdName(
    val id: Int,
    val name: String,
    val source: IdNameSource,
    val origin: String,
)

@Serializable
data class AnalyzerReport(
    val apk: String,
    val dexes: List<String>,
    val loadedClasses: Int,
    val visitedClasses: Int,
    val unresolvedOptions: Int,
    val jadxErrors: Int = 0,
    val elapsedMs: Long,
    val names: List<IdName>,
    val trackUnits: Map<Int, Int> = emptyMap(),
    val trackFamilies: Map<String, String> = emptyMap(),
    val facts: List<String> = emptyList(),
)
