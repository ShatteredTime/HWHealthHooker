package moe.evil.hwhh.xposed.hooks

import com.highcapable.kavaref.extension.classOf
import com.huawei.health.messagecenter.model.MessageObject
import moe.evil.hwhh.kdxref.HostBridge
import moe.evil.hwhh.kdxref.hostField
import moe.evil.hwhh.kdxref.hostMethod
import moe.evil.hwhh.kdxref.safeHook
import moe.evil.hwhh.kdxref.toClassOrLog
import moe.evil.hwhh.shared.HookRoot
import moe.evil.hwhh.xposed.utils.DexKitBaseHooker
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
        val mcaClassName = context(this@MessageCenterHooker) {
            "com.huawei.pluginmessagecenter.activity.MessageCenterActivity".toClassOrLog()
        }?.name ?: return
        val mclaClassName = context(this@MessageCenterHooker) {
            "com.huawei.pluginmessagecenter.adapter.MessageCenterListAdapter".toClassOrLog()
        }?.name ?: return

        val kakaFlag = hostField<Boolean>(
            label = "MessageCenterActivity#kakaFlag",
            inPackage = ACTIVITY_PACKAGE,
        ) {
            declaredClass = mcaClassName
            addWriteMethod { usingStrings = KAKA_MARKERS }
        } ?: return

        // suppress kaka message object creation (dexkit-resolved)
        hostMethod<Any>("MessageCenterActivity#buildKaka", ACTIVITY_PACKAGE) {
            declaredClass = mcaClassName
            usingStrings = KAKA_MARKERS
        }?.safeHook {
            replaceAny {
                kakaFlag.set(instanceOrNull, false)
                null
            }
        }

        // filter kaka messages from the assembled list (dexkit-resolved)
        hostMethod<Any>("MessageCenterActivity#getMessageList", ACTIVITY_PACKAGE) {
            declaredClass = mcaClassName
            paramTypes(classOf<Int>(), classOf<CountDownLatch>())
            usingStrings = listOf("handleMessageCenter reached")
        }?.safeHook {
            after {
                result = filterKakaMessages(result)
            }
        }

        // filter kaka messages before adapter display (dexkit-resolved)
        hostMethod<Unit>("MessageCenterListAdapter#setList", ADAPTER_PACKAGE) {
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
