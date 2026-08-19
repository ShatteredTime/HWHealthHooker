package moe.evil.hwhh.xposed.hooks.metadata

import com.highcapable.kavaref.extension.classOf
import com.huawei.hihealth.data.type.HiHealthDataType
import moe.evil.hwhh.kdxref.HostBridge
import moe.evil.hwhh.kdxref.HostMethod
import moe.evil.hwhh.kdxref.hostMethod

internal interface HealthTypeClassifierApi : MetadataSourceApi {
    fun classify(type: Int): HiHealthDataType.Category?
}

internal object HealthTypeClassifierHooker :
    MetaDataBaseHooker<HealthTypeClassifierApi>(isMajor = false) {
    @Volatile
    private var classifier: HostMethod<HiHealthDataType.Category>? = null

    override val providedApi = object : HealthTypeClassifierApi {
        override val isMajor get() = this@HealthTypeClassifierHooker.isMajor
        override val isAvailable get() = this@HealthTypeClassifierHooker.isAvailable
        override val availabilityError get() = this@HealthTypeClassifierHooker.availabilityError
        override fun classify(type: Int) = classifier?.invokeQuietly(type)
    }

    override fun onHookWithDexKit(bridge: HostBridge) {
        classifier = checkNotNull(
            hostMethod<HiHealthDataType.Category>(
                label = "HiHealthDataType#classify",
                pick = { singleOrNull { it.isStatic && it.isPublic } },
            ) {
                declaredClass(classOf<HiHealthDataType>())
                paramTypes(classOf<Int>())
            }
        ) { "HiHealthDataType classifier not resolved" }
    }
}
