package moe.evil.hwhh.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class AnalyzedNames(
    val host: String = "",
    val generatedAt: Long = 0,
    val names: Map<Int, String> = emptyMap(),
    val trackUnits: Map<Int, Int> = emptyMap(),
    val trackFamilies: Map<String, String> = emptyMap(),
)
