package moe.evil.hwhh.xposed.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class HealthInsightsManifest(
    val mmapId: String,
    val storeKey: String,
    val keySource: String,
    val userId: String,
    val exportedAtMs: Long,
    val generationTimeMs: Long,
    val cardCount: Int,
    val cardsWithData: Int,
)

@Serializable
internal data class HealthInsightsPayload(
    val highlights: HealthInsightsHighlights = HealthInsightsHighlights(),
)

@Serializable
internal data class HealthInsightsHighlights(
    val domainCards: List<HealthInsightsCard> = emptyList(),
)

@Serializable
internal data class HealthInsightsCard(
    val cardId: Int = 0,
    val featureName: String = "",
    val cardErrorCode: Int = -1,
    val generationTime: Long = 0,
)

@Serializable
internal data class HealthTrendsManifest(
    val mmapId: String,
    val userId: String,
    val exportedAtMs: Long,
    val cacheUpdatedAtMs: Long,
    val items: List<String>,
    val reportCount: Int,
    val pointCount: Int,
)

@Serializable
internal data class HealthTrendReport(
    val item: String = "",
    val trendPeriod: Int = 0,
    val startDate: Int = 0,
    val endDate: Int = 0,
    val statValues: List<JsonElement> = emptyList(),
)
