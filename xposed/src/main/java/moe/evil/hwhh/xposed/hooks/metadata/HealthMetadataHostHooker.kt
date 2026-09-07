package moe.evil.hwhh.xposed.hooks.metadata

import android.content.Context
import com.huawei.hihealth.data.constant.HiHealthDataKey
import com.huawei.hihealth.data.type.HiHealthDataType
import com.huawei.hihealth.dictionary.HiHealthDictManager
import com.huawei.hihealth.dictionary.model.HiHealthDictionary
import com.huawei.hihealth.dictionary.utils.DicDataTypeUtil
import com.huawei.hihealthservice.store.stat.HiDicHealthDataStat
import com.huawei.hihealthservice.store.stat.HiTrackStat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.xposed.model.NameSource
import moe.evil.hwhh.xposed.utils.Memo
import moe.evil.hwhh.xposed.utils.wrapper.HostBridge
import moe.evil.hwhh.xposed.utils.wrapper.Modifiers
import moe.evil.hwhh.xposed.utils.wrapper.classOf
import moe.evil.hwhh.xposed.utils.wrapper.fields
import moe.evil.hwhh.xposed.utils.wrapper.getOrNull
import moe.evil.hwhh.xposed.utils.wrapper.hostClass
import moe.evil.hwhh.xposed.utils.wrapper.hostGsonToJson
import moe.evil.hwhh.xposed.utils.wrapper.invokeOrNull
import moe.evil.hwhh.xposed.utils.wrapper.method
import moe.evil.hwhh.xposed.utils.wrapper.orWarnEmpty
import moe.evil.hwhh.xposed.utils.wrapper.requireMethod

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
    private val log = HLog.of<HealthMetadataHostHooker>()
    private val json = Json { ignoreUnknownKeys = true }

    private class Members(
        val dictionaryJson: () -> String?,
        val trackIdArrays: () -> List<HostTrackArray>,
        val pairedArrays: () -> List<HostPairedArrays>,
    )

    private lateinit var members: Members

    private val dictionaryEntries = Memo<Map<Int, HostDictionaryEntry>>()

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
            members.pairedArrays().forEach { (ids, keys) ->
                if (ids.size != keys.size) return@forEach
                ids.forEachIndexed { index, id ->
                    if (id > 0) keys[index]?.takeIf(String::isNotEmpty)?.let { put(id, it) }
                }
            }
        }.also { log.debug { "Paired key names=${it.size}" } }
    }

    override val providedApi: HealthMetadataHostApi =
        object : HealthMetadataHostApi, MetadataSourceApi by availability {
            override val enumNames get() = this@HealthMetadataHostHooker.enumNames
            override val keyNames get() = this@HealthMetadataHostHooker.keyNames
            override val trackIdArrays get() = members.trackIdArrays()
            override fun dictionary() = dictionaryEntries.orCatching {
                val raw = checkNotNull(members.dictionaryJson()) { "Host dictionary unreadable" }
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
            }
        }

    override fun onHookWithDexKit(bridge: HostBridge) {
        val dataType = classOf<HiHealthDataType>()
        val manager = classOf<HiHealthDictManager>()
        val managerOf = bridge.requireMethod<HiHealthDictManager>(
            label = "HiHealthDictManager#instance",
            pick = { singleOrNull { it.isStatic && it.isPublic } },
        ) {
            declaredClass(manager)
            paramTypes(classOf<Context>())
        }
        val dictionaryOf = bridge.requireMethod<HiHealthDictionary>(
            label = "HiHealthDictManager#dictionary",
        ) {
            declaredClass(manager)
            paramTypes()
        }

        val trackReaders = listOfNotNull(hostClass<HiTrackStat>(), hostClass<HiDicHealthDataStat>())
            .flatMap { clazz ->
                clazz.fields {
                    modifiers(Modifiers.STATIC)
                    type {
                        it.isArray &&
                                it.componentType == classOf<Int>(primitiveType = false)
                    }
                }.orWarnEmpty("static Integer[] field in ${clazz.name}").map { field ->
                    {
                        (field.getOrNull(null) as? Array<*>)?.filterIsInstance<Int>()?.let { ids ->
                            HostTrackArray("${field.owner.name}.${field.name}", ids)
                        } ?: null.also {
                            log.warn { "Track array ${field.name} unreadable" }
                        }
                    }
                }
            }.also { log.debug { "Track array readers=${it.size}" } }

        val pairedGetters = hostClass<HiHealthDataKey>()?.let { key ->
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
                val idGetter = dataType.method(classOf<IntArray>()) {
                    name = idName
                    emptyParameters()
                } ?: return@mapNotNull null
                val keyGetter = key.method(classOf<Array<String>>()) {
                    name = keyName
                    emptyParameters()
                } ?: return@mapNotNull null
                idGetter to keyGetter
            }
        }.orEmpty().also { log.debug { "Paired array getters=${it.size}" } }

        members = Members(
            dictionaryJson = {
                managerOf.invokeOrNull(null, null)
                    ?.let { dictionaryOf.invokeOrNull(it) }
                    ?.hostGsonToJson()
            },
            trackIdArrays = { trackReaders.mapNotNull { it() } },
            pairedArrays = {
                pairedGetters.mapNotNull { (idGetter, keyGetter) ->
                    val ids = idGetter.invokeOrNull(null)?.toList() ?: return@mapNotNull null
                    val keys = keyGetter.invokeOrNull(null)?.toList() ?: return@mapNotNull null
                    HostPairedArrays(ids, keys)
                }
            },
        )
    }
}
