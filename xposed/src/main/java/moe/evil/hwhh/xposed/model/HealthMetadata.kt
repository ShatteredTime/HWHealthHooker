package moe.evil.hwhh.xposed.model

import kotlinx.serialization.Serializable
import moe.evil.hwhh.shared.model.HealthCategory

enum class Aggregation { MIN, MAX, AVG, SUM, LAST, COUNT }

enum class TypeOrigin { FIXED, DICT }

enum class NameSource {
    DATATYPE_ENUM, DICT_FIELD, DICT_STAT, DICT_TYPE, TRACK_SLOT, KEY_ARRAY, ANALYZED, ALIAS_HOOK
}

@Serializable
data class HealthMetadata(
    val type: Int,
    val category: HealthCategory,
    val origin: TypeOrigin,
    val name: String? = null,
    val unit: String? = null,
    val unitId: Int? = null,
    val baseType: Int? = null,
    val aggregation: Aggregation? = null,
    val nameSource: NameSource? = null,
)
