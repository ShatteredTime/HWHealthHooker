package moe.evil.hwhh.xposed.hooks.metadata

import android.content.Context
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.classOf
import com.huawei.hihealth.data.constant.HiHealthDataKey
import com.huawei.hihealth.data.type.HiHealthDataType
import com.huawei.hihealth.dictionary.HiHealthDictManager
import com.huawei.hihealth.dictionary.model.HiHealthDictionary
import com.huawei.hihealth.dictionary.utils.DicDataTypeUtil
import com.huawei.hihealthservice.store.stat.HiDicHealthDataStat
import com.huawei.hihealthservice.store.stat.HiTrackStat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.evil.hwhh.kdxref.HostBridge
import moe.evil.hwhh.kdxref.hostGsonToJson
import moe.evil.hwhh.kdxref.hostMethod
import moe.evil.hwhh.kdxref.orWarnEmpty
import moe.evil.hwhh.kdxref.resolveAny
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.xposed.model.NameSource

@Serializable
private data class DictJson(val dictTypes: List<DictTypeJson> = emptyList())

@Serializable
private data class DictTypeJson(
    val typeId: Int = 0,
    val name: String? = null,
    val fields: List<DictFieldJson> = emptyList(),
)

@Serializable
private data class DictFieldJson(
    val healthType: Int = 0,
    val name: String? = null,
    val unit: String? = null,
    val statPolicies: List<DictStatJson> = emptyList(),
)

@Serializable
private data class DictStatJson(
    val statType: Int = 0,
    val statFieldName: String? = null,
    val statPolicy: String? = null,
)

private data class HostPairedArrays(
    val ids: List<Int>,
    val keys: List<String?>,
)

internal interface HealthMetadataHostApi : MetadataSourceApi {
    val enumNames: Map<Int, String>
    val keyNames: Map<Int, String>
    val trackIdArrays: List<HostTrackArray>
    fun dictionary(): Result<Map<Int, HostDictionaryEntry>>
}

internal object HealthMetadataHostHooker :
    MetaDataBaseHooker<HealthMetadataHostApi>() {
    private const val KEY_CLASS = "com.huawei.hihealth.data.constant.HiHealthDataKey"
    private const val TRACK_CLASS = "com.huawei.hihealthservice.store.stat.HiTrackStat"
    private const val DICT_TRACK_CLASS = "com.huawei.hihealthservice.store.stat.HiDicHealthDataStat"
    private const val DICT_MANAGER_CLASS = "com.huawei.hihealth.dictionary.HiHealthDictManager"
    private val log = HLog.of<HealthMetadataHostHooker>()
    private val json = Json { ignoreUnknownKeys = true }

    private class Members(
        val dictionaryJson: () -> String?,
        val trackIdArrays: () -> List<HostTrackArray>,
        val pairedArrays: () -> List<HostPairedArrays>,
    )

    @Volatile
    private var members: Members? = null

    @Volatile
    private var dictionaryEntries: Map<Int, HostDictionaryEntry>? = null

    private val enumNames by lazy {
        var unreadable = 0
        classOf<DicDataTypeUtil.DataType>().enumConstants.orEmpty()
            .fold(HashMap<Int, String>()) { names, constant ->
                val id = runCatching { constant.value() }.onFailure { unreadable++ }.getOrNull()
                if (id != null && id > 0) {
                    names.merge(id, constant.name) { old, new ->
                        if (new.length > old.length) new else old
                    }
                }
                names
            }
            .also {
                if (unreadable > 0) log.warn { "DataType.value() failed on $unreadable constants" }
                log.debug { "Enum names=${it.size}" }
            }
    }

    private val keyNames by lazy {
        buildMap {
            members?.pairedArrays?.invoke().orEmpty().forEach { (ids, keys) ->
                if (ids.size != keys.size) return@forEach
                ids.forEachIndexed { index, id ->
                    if (id > 0) keys[index]?.takeIf(String::isNotEmpty)?.let { put(id, it) }
                }
            }
        }.also { log.debug { "Paired key names=${it.size}" } }
    }

    private val trackIdArrays
        get() = members?.trackIdArrays?.invoke().orEmpty()

    override val providedApi = object : HealthMetadataHostApi {
        override val isMajor get() = this@HealthMetadataHostHooker.isMajor
        override val isAvailable get() = this@HealthMetadataHostHooker.isAvailable
        override val availabilityError get() = this@HealthMetadataHostHooker.availabilityError
        override val enumNames get() = this@HealthMetadataHostHooker.enumNames
        override val keyNames get() = this@HealthMetadataHostHooker.keyNames
        override val trackIdArrays get() = this@HealthMetadataHostHooker.trackIdArrays
        override fun dictionary() =
            dictionaryEntries?.let { Result.success(it) }
                ?: synchronized(this@HealthMetadataHostHooker) {
                    dictionaryEntries?.let { Result.success(it) } ?: runCatching {
                        val raw = checkNotNull(members?.dictionaryJson?.invoke()) {
                            "Host dictionary unreadable"
                        }
                        json.decodeFromString<DictJson>(raw).let { parsed ->
                            buildMap {
                                parsed.dictTypes.forEach { dataType ->
                                    dataType.typeId.takeIf { it > 0 }?.let {
                                        this[it] = HostDictionaryEntry(
                                            dataType.name,
                                            null,
                                            null,
                                            NameSource.DICT_TYPE,
                                        )
                                    }
                                    dataType.fields.forEach { field ->
                                        val unit = field.unit?.takeIf(String::isNotEmpty)
                                        field.healthType.takeIf { it > 0 }?.let {
                                            this[it] = HostDictionaryEntry(
                                                field.name,
                                                unit,
                                                null,
                                                NameSource.DICT_FIELD,
                                            )
                                        }
                                        field.statPolicies.forEach { stat ->
                                            stat.statType.takeIf { it > 0 }?.let {
                                                this[it] = HostDictionaryEntry(
                                                    stat.statFieldName?.let { fieldName ->
                                                        stat.statPolicy?.let { policy ->
                                                            "$fieldName ${policy.lowercase()}"
                                                        } ?: fieldName
                                                    },
                                                    unit,
                                                    stat.statPolicy,
                                                    NameSource.DICT_STAT,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }.onSuccess { dictionaryEntries = it }
                }
    }

    override fun onHookWithDexKit(bridge: HostBridge) {
        val dataType = classOf<HiHealthDataType>()
        val manager = runCatching { classOf<HiHealthDictManager>() }
            .getOrElse { cause ->
                throw IllegalStateException(
                    "Class NOT found: $DICT_MANAGER_CLASS",
                    cause
                )
            }
        val dataTypeScope = dataType.resolveAny()
        val managerOf = checkNotNull(
            hostMethod<HiHealthDictManager>(
                label = "HiHealthDictManager#instance",
                pick = { singleOrNull { it.isStatic && it.isPublic } },
            ) {
                declaredClass(manager)
                paramTypes(classOf<Context>())
            }
        ) { "HiHealthDictManager factory not resolved" }
        val dictionaryOf = checkNotNull(
            hostMethod<HiHealthDictionary>("HiHealthDictManager#dictionary") {
                declaredClass(manager)
                paramTypes()
            }
        ) { "HiHealthDictionary accessor not resolved" }

        val trackReaders = listOf(
            TRACK_CLASS to runCatching { classOf<HiTrackStat>() },
            DICT_TRACK_CLASS to runCatching { classOf<HiDicHealthDataStat>() },
        ).flatMap { (className, loaded) ->
            loaded
                .onFailure { log.warn { "Class not found: $className" } }
                .getOrNull()
                ?.resolveAny()
                ?.fields {
                    modifiers(Modifiers.STATIC)
                    type {
                        it.isArray &&
                                it.componentType == classOf<Int>(primitiveType = false)
                    }
                }
                ?.orWarnEmpty("static Integer[] field in $className")
                .orEmpty()
                .map { field ->
                    {
                        (field.get() as? Array<*>)?.filterIsInstance<Int>()?.let { ids ->
                            HostTrackArray("${field.owner.name}.${field.name}", ids)
                        } ?: null.also {
                            log.warn { "Track array ${field.name} unreadable" }
                        }
                    }
                }
        }.also { log.debug { "Track array readers=${it.size}" } }

        val keyType = runCatching { classOf<HiHealthDataKey>() }
            .onFailure { log.warn { "Class not found: $KEY_CLASS" } }
            .getOrNull()
        val pairedGetters = keyType?.let { key ->
            val keyScope = key.resolveAny()
            bridge.findMethod {
                matcher {
                    addInvoke {
                        declaredClass(dataType)
                        returnType(classOf<IntArray>())
                        paramCount = 0
                    }
                    addInvoke {
                        declaredClass(key)
                        returnType(classOf<Array<String>>())
                        paramCount = 0
                    }
                }
            }.mapNotNull { caller ->
                val idNames = HashSet<String>()
                val keyNames = HashSet<String>()
                caller.invokes.forEach { invoked ->
                    when (invoked.className) {
                        dataType.name if invoked returns classOf<IntArray>() ->
                            idNames += invoked.name

                        key.name if invoked returns classOf<Array<String>>() ->
                            keyNames += invoked.name
                    }
                }
                val idName = idNames.singleOrNull()
                val keyName = keyNames.singleOrNull()
                if (idName == null || keyName == null) null else idName to keyName
            }.distinct().mapNotNull { (idName, keyName) ->
                val idGetter = dataTypeScope.firstMethodOrNullLogged(
                    dataType,
                    classOf<IntArray>(),
                ) {
                    name = idName
                    emptyParameters()
                } ?: return@mapNotNull null
                val keyGetter = keyScope.firstMethodOrNullLogged(
                    key,
                    classOf<Array<String>>(),
                ) {
                    name = keyName
                    emptyParameters()
                } ?: return@mapNotNull null
                idGetter to keyGetter
            }
        }.orEmpty().also { log.debug { "Paired array getters=${it.size}" } }

        members = Members(
            dictionaryJson = {
                managerOf.invokeQuietly(null)
                    ?.let { dictionaryOf.on(it).invokeQuietly() }
                    ?.hostGsonToJson()
            },
            trackIdArrays = { trackReaders.mapNotNull { it() } },
            pairedArrays = {
                pairedGetters.mapNotNull { (idGetter, keyGetter) ->
                    val ids = idGetter.invokeQuietly()?.toList() ?: return@mapNotNull null
                    val keys = keyGetter.invokeQuietly()?.toList() ?: return@mapNotNull null
                    HostPairedArrays(ids, keys)
                }
            },
        )
    }
}
