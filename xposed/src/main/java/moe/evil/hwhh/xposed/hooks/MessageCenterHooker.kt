package moe.evil.hwhh.xposed.hooks

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.huawei.health.messagecenter.model.MessageObject
import moe.evil.hwhh.xposed.utils.DexKitHooker
import moe.evil.hwhh.xposed.utils.firstMethodOrNullLogged
import moe.evil.hwhh.xposed.utils.safeHook
import moe.evil.hwhh.xposed.utils.toClassOrLog
import moe.evil.hwhh.xposed.utils.tryHookWithDexKit
import java.util.concurrent.CountDownLatch

object MessageCenterHooker : DexKitHooker() {
    private const val KAKA_MODULE = "17"
    private const val KAKA_MESSAGE_ID = "kakaMessage"
    private val KAKA_TYPES = setOf("unclaimedKaka", "kakaExpiration")

    override fun onHook() = tryHookWithDexKit { bridge ->
        val messageCenterActivityClazz = context(this@MessageCenterHooker) {
            "com.huawei.pluginmessagecenter.activity.MessageCenterActivity".toClassOrLog()
        } ?: return@tryHookWithDexKit
        val messageCenterListAdapterClazz = context(this@MessageCenterHooker) {
            "com.huawei.pluginmessagecenter.adapter.MessageCenterListAdapter".toClassOrLog()
        } ?: return@tryHookWithDexKit
        val mcaClassName = messageCenterActivityClazz.name
        val mclaClassName = messageCenterListAdapterClazz.name

        val buildKakaMethodName = bridge.findMethod {
            searchPackages("com.huawei.pluginmessagecenter.activity")
            matcher {
                declaredClass = mcaClassName
                usingStrings = listOf("kakaMessage", "unclaimedKaka")
            }
        }.single().name

        val getMessageListMethodName = bridge.findMethod {
            searchPackages("com.huawei.pluginmessagecenter.activity")
            matcher {
                declaredClass = mcaClassName
                paramTypes("int", "java.util.concurrent.CountDownLatch")
                usingStrings = listOf("handleMessageCenter reached")
            }
        }.single().name

        val setListMethodName = bridge.findMethod {
            searchPackages("com.huawei.pluginmessagecenter.adapter")
            matcher {
                declaredClass = mclaClassName
                paramTypes("java.util.List")
                returnType = "void"
            }
        }.single().name

        val kakaFlagFieldName = bridge.findField {
            searchPackages("com.huawei.pluginmessagecenter.activity")
            matcher {
                declaredClass = mcaClassName
                type = "boolean"
                addWriteMethod {
                    usingStrings = listOf("kakaMessage", "unclaimedKaka")
                }
            }
        }.single().name

        // suppress kaka message object creation (dexkit-resolved)
        messageCenterActivityClazz.firstMethodOrNullLogged {
            name = buildKakaMethodName
        }?.safeHook {
            replaceAny {
                instanceOrNull?.asResolver()?.optional(silent = true)?.firstFieldOrNull {
                    name = kakaFlagFieldName
                    type = Boolean::class
                }?.setQuietly(false)
                null
            }
        }

        // filter kaka messages from the assembled list (dexkit-resolved)
        messageCenterActivityClazz.firstMethodOrNullLogged {
            name = getMessageListMethodName
            parameters(Int::class, CountDownLatch::class)
        }?.safeHook {
            after {
                result = filterKakaMessages(result)
            }
        }

        // filter kaka messages before adapter display (dexkit-resolved)
        messageCenterListAdapterClazz.firstMethodOrNullLogged {
            name = setListMethodName
            parameters(List::class)
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

    private fun MessageObject.isKaka(): Boolean =
        msgId == KAKA_MESSAGE_ID || module == KAKA_MODULE || type in KAKA_TYPES
}
