package moe.evil.hwhh.xposed.hooks.metadata

import moe.evil.hwhh.xposed.model.NameSource
import moe.evil.hwhh.xposed.utils.HookApi

internal interface MetadataSourceApi : HookApi {
    val isMajor: Boolean
    val isAvailable: Boolean
    val availabilityError: Throwable?
}

internal data class DbMetadataSnapshot(
    val units: Map<Int, Int>,
    val bases: Map<Int, Int>,
)

internal data class HostDictionaryEntry(
    val name: String?,
    val unit: String?,
    val statPolicy: String?,
    val source: NameSource,
)

internal data class HostTrackArray(
    val source: String,
    val ids: List<Int>,
)

internal data class TrackSlot(
    val name: String,
    val unitId: Int,
)
