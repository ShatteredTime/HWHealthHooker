package moe.evil.hwhh.xposed.hooks

import com.huawei.health.messagecenter.model.MessageObject
import moe.evil.hwhh.shared.HookRoot
import moe.evil.hwhh.xposed.utils.DexKitBaseHooker
import moe.evil.hwhh.xposed.utils.wrapper.HostBridge
import moe.evil.hwhh.xposed.utils.wrapper.classOf
import moe.evil.hwhh.xposed.utils.wrapper.method
import moe.evil.hwhh.xposed.utils.wrapper.requireClass
import moe.evil.hwhh.xposed.utils.wrapper.requireField
import moe.evil.hwhh.xposed.utils.wrapper.safeHook
import moe.evil.hwhh.xposed.utils.wrapper.trySet
import java.util.concurrent.CountDownLatch

@HookRoot(order = 1)
object MessageCenterHooker : DexKitBaseHooker() {
    private const val KAKA_MODULE = "17"
    private const val KAKA_MESSAGE_ID = "kakaMessage"
    private const val ACTIVITY_PACKAGE = "com.huawei.pluginmessagecenter.activity"
    private const val ADAPTER_PACKAGE = "com.huawei.pluginmessagecenter.adapter"
    private val KAKA_TYPES = setOf("unclaimedKaka", "kakaExpiration")
    private val KAKA_MARKERS = listOf("kakaMessage", "unclaimedKaka")

    override fun onHookWithDexKit(bridge: HostBridge) {
        val mcaClassName = bridge.requireClass("$ACTIVITY_PACKAGE.MessageCenterActivity").name
        val mclaClassName = bridge.requireClass("$ADAPTER_PACKAGE.MessageCenterListAdapter").name

        val kakaFlag = bridge.requireField<Boolean>(
            label = "MessageCenterActivity#kakaFlag",
            inPackage = ACTIVITY_PACKAGE,
        ) {
            declaredClass = mcaClassName
            addWriteMethod { usingStrings = KAKA_MARKERS }
        }

        // suppress kaka message object creation (dexkit-resolved)
        bridge.method<Any>("MessageCenterActivity#buildKaka", ACTIVITY_PACKAGE) {
            declaredClass = mcaClassName
            usingStrings = KAKA_MARKERS
        }?.safeHook {
            replaceAny {
                kakaFlag.trySet(instanceOrNull, false)
                null
            }
        }

        // filter kaka messages from the assembled list (dexkit-resolved)
        bridge.method<Any>("MessageCenterActivity#getMessageList", ACTIVITY_PACKAGE) {
            declaredClass = mcaClassName
            paramTypes(classOf<Int>(), classOf<CountDownLatch>())
            usingStrings = listOf("handleMessageCenter reached")
        }?.safeHook {
            after {
                result = filterKakaMessages(result)
            }
        }

        // filter kaka messages before adapter display (dexkit-resolved)
        bridge.method<Unit>("MessageCenterListAdapter#setList", ADAPTER_PACKAGE) {
            declaredClass = mclaClassName
            paramTypes(classOf<List<*>>())
        }?.safeHook {
            before {
                args(0).set(filterKakaMessages(args(0).any()))
            }
        }
    }

    private fun filterKakaMessages(any: Any?): MutableList<Any?> {
        val source = any as? List<*> ?: return mutableListOf()
        return source.filterNotTo(mutableListOf()) { it is MessageObject && it.isKaka() }
    }

    private fun MessageObject.isKaka() =
        msgId == KAKA_MESSAGE_ID || module == KAKA_MODULE || type in KAKA_TYPES
}
