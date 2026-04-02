package moe.evil.hwhh.xposed.hooks

import android.content.Context
import android.view.View
import com.highcapable.yukihookapi.hook.log.YLog
import moe.evil.hwhh.xposed.HOOK_TARGET_PACKAGE
import moe.evil.hwhh.xposed.utils.DexKitHooker
import moe.evil.hwhh.xposed.utils.collapseView
import moe.evil.hwhh.xposed.utils.firstMethodOrNullLogged
import moe.evil.hwhh.xposed.utils.safeHook
import moe.evil.hwhh.xposed.utils.toClassOrLog
import moe.evil.hwhh.xposed.utils.tryHook

object PersonalCenterHooker : DexKitHooker() {
    private const val KAKA_BOTTOM_RED_DOT_POSITION = 4
    private const val MESSAGE_BOTTOM_RED_DOT_POSITION = 1
    private const val KAKA_PENDING_ID = "kaka_to_be_collected_text"

    override fun onHook() = tryHook {
        val customHeadViewClazz = context(this@PersonalCenterHooker) {
            "com.huawei.ui.main.stories.userprofile.scroll.CustomHeadView".toClassOrLog()
        } ?: return@tryHook
        val personalCenterFragmentClazz = context(this@PersonalCenterHooker) {
            "com.huawei.ui.main.stories.userprofile.activity.PersonalCenterFragment".toClassOrLog()
        } ?: return@tryHook

        customHeadViewClazz.firstMethodOrNullLogged {
            name = "b"
            parameters(Int::class)
        }?.safeHook {
            before {
                args(0).set(View.GONE)
            }
            after {
                hideKakaPendingText(instanceOrNull)
            }
        }

        customHeadViewClazz.firstMethodOrNullLogged {
            name = "a"
            parameters(String::class)
        }?.safeHook {
            before {
                hideKakaPendingText(instanceOrNull)
            }
            after {
                hideKakaPendingText(instanceOrNull)
            }
        }

        personalCenterFragmentClazz.firstMethodOrNullLogged {
            name = "b"
            parameters(Int::class)
        }?.safeHook {
            var fixedCount = 0
            before {
                val original = args(0).int()
                fixedCount = if (hasKakaRedDot()) {
                    (original - 1).coerceAtLeast(0)
                } else {
                    original
                }
                YLog.debug("PersonalCenterFragment#b: original=$original, fixed=$fixedCount")
                args(0).set(fixedCount)
            }
            after {
                if (fixedCount == 0) {
                    cancelBottomRedDot(instanceOrNull, MESSAGE_BOTTOM_RED_DOT_POSITION)
                }
            }
        }

        personalCenterFragmentClazz.firstMethodOrNullLogged {
            name = "an"
        }?.safeHook {
            after {
                cancelBottomRedDot(instanceOrNull, KAKA_BOTTOM_RED_DOT_POSITION)
            }
        }

        personalCenterFragmentClazz.firstMethodOrNullLogged {
            name = "setBottomRedDotVisibility"
            parameters(Int::class)
        }?.safeHook {
            before {
                traceBottomRedDotMutation(instanceOrNull, "set", args(0).int())
            }
            after {
                traceBottomRedDotMutation(instanceOrNull, "set-after", args(0).int())
            }
        }

        personalCenterFragmentClazz.firstMethodOrNullLogged {
            name = "cancelBottomRedDotVisible"
            parameters(Int::class)
        }?.safeHook {
            before {
                traceBottomRedDotMutation(instanceOrNull, "remove", args(0).int())
            }
            after {
                traceBottomRedDotMutation(instanceOrNull, "remove-after", args(0).int())
            }
        }
    }

    private fun hideKakaPendingText(host: Any?) {
        val rootView = host as? View ?: return
        val textId = runCatching {
            rootView.resources.getIdentifier(KAKA_PENDING_ID, "id", HOOK_TARGET_PACKAGE)
        }.getOrDefault(0)
        if (textId == 0) return
        collapseView(rootView.findViewById(textId))
    }

    private fun hasKakaRedDot(): Boolean {
        val context = getBaseContext() ?: return false
        return runCatching {
            val manager = context(this@PersonalCenterHooker) {
                "nqj".toClassOrLog()
            }?.getMethod("c", Context::class.java)
                ?.invoke(null, context)
                ?: return@runCatching false
            manager.javaClass.getMethod("b").invoke(manager) as? Boolean ?: false
        }.getOrDefault(false)
    }

    private fun getBaseContext(): Context? = runCatching {
        context(this@PersonalCenterHooker) {
            "com.huawei.hwcommonmodel.application.BaseApplication".toClassOrLog()
        }?.getMethod("getContext")
            ?.invoke(null) as? Context
    }.getOrNull()

    private fun traceBottomRedDotMutation(host: Any?, action: String, position: Int) {
        // TODO: use more robust way to adjust
        if (!moe.evil.hwhh.xposed.BuildConfig.DEBUG) return
        val map = getBottomRedDotMap(host)
        val stack = Throwable()
            .stackTrace
            .filter {
                it.className.contains("PersonalCenterFragment") ||
                        it.className.contains("$HOOK_TARGET_PACKAGE.MainActivity") ||
                        it.className.startsWith("defpackage.vbd") ||
                        it.className.startsWith("defpackage.vag") ||
                        it.className.startsWith("defpackage.uzw")
            }
            .take(12)
            .joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        YLog.debug("bottomRedDot action=$action position=$position map=$map stack=$stack")
    }

    private fun getBottomRedDotMap(host: Any?): Map<*, *>? {
        if (host == null) return null
        return runCatching {
            host.javaClass.getDeclaredField("i")
                .apply { isAccessible = true }.get(host) as? Map<*, *>
        }.recoverCatching {
            host.javaClass.getDeclaredField("f71311i")
                .apply { isAccessible = true }.get(host) as? Map<*, *>
        }.getOrNull()
    }

    private fun cancelBottomRedDot(host: Any?, position: Int) {
        if (host == null) return
        runCatching {
            host.javaClass
                .getMethod(
                    "cancelBottomRedDotVisible",
                    Int::class.javaPrimitiveType ?: Int::class.javaObjectType
                )
                .invoke(host, position)
        }
    }

}
