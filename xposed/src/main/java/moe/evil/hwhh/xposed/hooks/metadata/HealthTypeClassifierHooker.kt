package moe.evil.hwhh.xposed.hooks.metadata

import com.huawei.hihealth.data.type.HiHealthDataType
import moe.evil.hwhh.xposed.utils.wrapper.HostBridge
import moe.evil.hwhh.xposed.utils.wrapper.HostMethod
import moe.evil.hwhh.xposed.utils.wrapper.classOf
import moe.evil.hwhh.xposed.utils.wrapper.invokeOrNull
import moe.evil.hwhh.xposed.utils.wrapper.requireMethod

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
        override fun classify(type: Int) = classifier?.invokeOrNull(null, type)
    }

    override fun onHookWithDexKit(bridge: HostBridge) {
        classifier = bridge.requireMethod<HiHealthDataType.Category>(
            label = "HiHealthDataType#classify",
            pick = { singleOrNull { it.isStatic && it.isPublic } },
        ) {
            declaredClass(classOf<HiHealthDataType>())
            paramTypes(classOf<Int>())
        }
    }
}
