package moe.evil.hwhh.xposed.hooks.metadata

import com.huawei.hihealth.HiDataReadOption
import moe.evil.hwhh.xposed.utils.wrapper.HostBridge
import moe.evil.hwhh.xposed.utils.wrapper.classOf
import moe.evil.hwhh.xposed.utils.wrapper.method
import moe.evil.hwhh.xposed.utils.wrapper.safeHook
import java.util.concurrent.ConcurrentHashMap

internal interface HealthAliasApi : MetadataSourceApi {
    val names: Map<Int, String>
}

internal object HealthAliasHooker : MetaDataBaseHooker<HealthAliasApi>() {
    private const val SPORT_STAT_OPTION = "com.huawei.hihealth.HiSportStatDataAggregateOption"
    private val aliases = ConcurrentHashMap<Int, ConcurrentHashMap<String, Int>>()

    private val names
        get() = buildMap {
            aliases.forEach { (type, byName) ->
                byName.entries.maxWithOrNull(
                    compareBy<Map.Entry<String, Int>> { it.value }
                        .thenByDescending { it.key.length },
                )?.let { put(type, it.key) }
            }
        }

    override val providedApi = object : HealthAliasApi {
        override val isMajor get() = this@HealthAliasHooker.isMajor
        override val isAvailable get() = this@HealthAliasHooker.isAvailable
        override val availabilityError get() = this@HealthAliasHooker.availabilityError
        override val names get() = this@HealthAliasHooker.names
    }

    override fun onHookWithDexKit(bridge: HostBridge) {
        val option = classOf<HiDataReadOption>()
        val setters = listOfNotNull(
            option.method {
                name = "setType"
                parameters(classOf<IntArray>())
            },
            option.method {
                name = "setType"
                parameters(classOf<IntArray>(), classOf<Array<String>>(), classOf<Int>())
            },
            option.method {
                name = "setConstantsKey"
                parameters(classOf<Array<String>>())
            },
        )
        check(setters.isNotEmpty()) { "No supported HiDataReadOption metadata setter was resolved" }
        setters.forEach { setter ->
            setter.safeHook {
                after {
                    val readOption = instance<HiDataReadOption>()
                    if (readOption.javaClass.name == SPORT_STAT_OPTION) return@after
                    val types = readOption.getType() ?: return@after
                    val keys = readOption.getConstantsKey() ?: return@after
                    if (types.isEmpty() || types.size != keys.size) return@after
                    types.forEachIndexed { index, type ->
                        keys[index]?.takeIf(String::isNotEmpty)?.let { key ->
                            aliases.getOrPut(type) { ConcurrentHashMap() }.merge(key, 1, Int::plus)
                        }
                    }
                }
            }
        }
    }
}
