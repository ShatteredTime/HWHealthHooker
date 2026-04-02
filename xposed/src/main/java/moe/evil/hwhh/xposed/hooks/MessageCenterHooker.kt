package moe.evil.hwhh.xposed.hooks

import moe.evil.hwhh.xposed.utils.DexKitHooker
import moe.evil.hwhh.xposed.utils.firstMethodOrNullLogged
import moe.evil.hwhh.xposed.utils.safeHook
import moe.evil.hwhh.xposed.utils.toClassOrLog
import moe.evil.hwhh.xposed.utils.tryHook
import java.util.concurrent.CountDownLatch

object MessageCenterHooker : DexKitHooker() {
    private const val KAKA_MODULE = "17"
    private const val KAKA_MESSAGE_ID = "kakaMessage"
    private val KAKA_TYPES = setOf("unclaimedKaka", "kakaExpiration")

    override fun onHook() = tryHook {
        val messageCenterActivityClazz = context(this@MessageCenterHooker) {
            "com.huawei.pluginmessagecenter.activity.MessageCenterActivity".toClassOrLog()
        } ?: return@tryHook
        val messageCenterListAdapterClazz = context(this@MessageCenterHooker) {
            "com.huawei.pluginmessagecenter.adapter.MessageCenterListAdapter".toClassOrLog()
        } ?: return@tryHook

        messageCenterActivityClazz.firstMethodOrNullLogged {
            name = "e"
        }?.safeHook {
            replaceAny {
                instanceOrNull?.let { host ->
                    runCatching {
                        host.javaClass.getDeclaredField("f")
                            .apply { isAccessible = true }.setBoolean(host, false)
                    }
                }
                null
            }
        }

        messageCenterActivityClazz.firstMethodOrNullLogged {
            name = "b"
            parameters(Int::class, CountDownLatch::class)
        }?.safeHook {
            after {
                result = filterKakaMessages(result)
            }
        }

        messageCenterListAdapterClazz.firstMethodOrNullLogged {
            name = "a"
            parameters(List::class)
        }?.safeHook {
            before {
                args(0).set(filterKakaMessages(args(0).any()))
            }
        }
    }

    private fun filterKakaMessages(any: Any?): MutableList<Any?> {
        val source = any as? List<*> ?: return mutableListOf()
        return source.filterNot { isKakaMessage(it) }.toMutableList()
    }

    private fun isKakaMessage(any: Any?): Boolean {
        if (any == null) return false
        val msgId = callStringGetter(any, "getMsgId")
        val module = callStringGetter(any, "getModule")
        val type = callStringGetter(any, "getType")
        return msgId == KAKA_MESSAGE_ID || module == KAKA_MODULE || type in KAKA_TYPES
    }

    private fun callStringGetter(any: Any, methodName: String): String? =
        runCatching { any.javaClass.getMethod(methodName).invoke(any) as? String }.getOrNull()

}
