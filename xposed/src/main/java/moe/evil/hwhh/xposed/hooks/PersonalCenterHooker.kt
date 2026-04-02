package moe.evil.hwhh.xposed.hooks

import android.content.Context
import android.view.View
import com.highcapable.yukihookapi.hook.log.YLog
import moe.evil.hwhh.xposed.DebugPrefs
import moe.evil.hwhh.xposed.HOOK_TARGET_PACKAGE
import moe.evil.hwhh.xposed.utils.DexKitHooker
import moe.evil.hwhh.xposed.utils.collapseView
import moe.evil.hwhh.xposed.utils.firstMethodOrNullLogged
import moe.evil.hwhh.xposed.utils.ifDebugPref
import moe.evil.hwhh.xposed.utils.safeHook
import moe.evil.hwhh.xposed.utils.toClassOrLog
import moe.evil.hwhh.xposed.utils.tryHookWithDexKit

object PersonalCenterHooker : DexKitHooker() {
    private const val KAKA_BOTTOM_RED_DOT_POSITION = 4
    private const val MESSAGE_BOTTOM_RED_DOT_POSITION = 1
    private const val KAKA_PENDING_ID = "kaka_to_be_collected_text"

    private var kakaManagerClassName: String? = null
    private var kakaManagerGetterName: String? = null
    private var kakaRedDotMethodName: String? = null
    private var bottomRedDotMapFieldName: String? = null
    private var redDotCallerClassNames: Set<String> = emptySet()

    override fun onHook() = tryHookWithDexKit { bridge ->
        val customHeadViewClazz = context(this@PersonalCenterHooker) {
            "com.huawei.ui.main.stories.userprofile.scroll.CustomHeadView".toClassOrLog()
        } ?: return@tryHookWithDexKit
        val personalCenterFragmentClazz = context(this@PersonalCenterHooker) {
            "com.huawei.ui.main.stories.userprofile.activity.PersonalCenterFragment".toClassOrLog()
        } ?: return@tryHookWithDexKit

        val kakaRedDotMethodData = bridge.findMethod {
            matcher {
                usingStrings = listOf("getKakaTaskRedDot enter")
                returnType = "boolean"
                paramCount(0)
            }
        }.single()

        val kmClassName = kakaRedDotMethodData.className
        kakaManagerClassName = kmClassName
        kakaRedDotMethodName = kakaRedDotMethodData.name
        kakaManagerGetterName = bridge.findMethod {
            matcher {
                declaredClass = kmClassName
                paramTypes("android.content.Context")
                returnType = kmClassName
            }
        }.single().name

        val chvKakaVisibilityName = bridge.findMethod {
            searchPackages("com.huawei.ui.main.stories.userprofile.scroll")
            matcher {
                declaredClass = "com.huawei.ui.main.stories.userprofile.scroll.CustomHeadView"
                modifiers = java.lang.reflect.Modifier.PUBLIC
                paramTypes("int")
                returnType = "void"
                addInvoke { name = "setVisibility" }
            }
        }.let { all ->
            val withFindView = bridge.findMethod {
                searchPackages("com.huawei.ui.main.stories.userprofile.scroll")
                matcher {
                    declaredClass = "com.huawei.ui.main.stories.userprofile.scroll.CustomHeadView"
                    modifiers = java.lang.reflect.Modifier.PUBLIC
                    paramTypes("int")
                    returnType = "void"
                    addInvoke { name = "setVisibility" }
                    addInvoke { name = "findViewById" }
                }
            }.map { it.name }.toSet()
            all.single { it.name !in withFindView }.name
        }

        val chvKakaTextSetterName = bridge.findMethod {
            searchPackages("com.huawei.ui.main.stories.userprofile.scroll")
            matcher {
                declaredClass = "com.huawei.ui.main.stories.userprofile.scroll.CustomHeadView"
                paramTypes("java.lang.String")
                returnType = "void"
                addInvoke { name = "getText" }
            }
        }.single().name

        val pcfSetUnreadMsgName = bridge.findMethod {
            searchPackages("com.huawei.ui.main.stories.userprofile.activity")
            matcher {
                declaredClass =
                    "com.huawei.ui.main.stories.userprofile.activity.PersonalCenterFragment"
                usingStrings = listOf("Enter setUnreadMessageNum unreadMessageNum:")
            }
        }.single().name

        val pcfKakaRedDotUpdaterName = bridge.findMethod {
            searchPackages("com.huawei.ui.main.stories.userprofile.activity")
            matcher {
                declaredClass =
                    "com.huawei.ui.main.stories.userprofile.activity.PersonalCenterFragment"
                returnType = "void"
                addInvoke { name = "setBottomRedDotVisibility" }
                addInvoke { name = "cancelBottomRedDotVisible" }
                addInvoke {
                    declaredClass = kmClassName
                    name = kakaRedDotMethodData.name
                }
            }
        }.single().name

        bottomRedDotMapFieldName = bridge.findField {
            searchPackages("com.huawei.ui.main.stories.userprofile.activity")
            matcher {
                declaredClass =
                    "com.huawei.ui.main.stories.userprofile.activity.PersonalCenterFragment"
                type = "java.util.Map"
            }
        }.single().name

        val pcfClassName = "com.huawei.ui.main.stories.userprofile.activity.PersonalCenterFragment"
        redDotCallerClassNames = bridge.findMethod {
            searchPackages("com.huawei.ui.main.stories.userprofile")
            matcher {
                addInvoke {
                    declaredClass = pcfClassName
                    name = "setBottomRedDotVisibility"
                }
            }
        }.map { it.className }.filter { it != pcfClassName }.toSet()

        // kaka visibility setter on CustomHeadView (dexkit-resolved)
        customHeadViewClazz.firstMethodOrNullLogged {
            name = chvKakaVisibilityName
            parameters(Int::class)
        }?.safeHook {
            before {
                args(0).set(View.GONE)
            }
            after {
                hideKakaPendingText(instanceOrNull)
            }
        }

        // kaka text setter on CustomHeadView (dexkit-resolved)
        customHeadViewClazz.firstMethodOrNullLogged {
            name = chvKakaTextSetterName
            parameters(String::class)
        }?.safeHook {
            before {
                hideKakaPendingText(instanceOrNull)
            }
            after {
                hideKakaPendingText(instanceOrNull)
            }
        }

        // unread message count setter (dexkit-resolved)
        personalCenterFragmentClazz.firstMethodOrNullLogged {
            name = pcfSetUnreadMsgName
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

        // kaka bottom red dot updater (dexkit-resolved)
        personalCenterFragmentClazz.firstMethodOrNullLogged {
            name = pcfKakaRedDotUpdaterName
        }?.safeHook {
            after {
                cancelBottomRedDot(instanceOrNull, KAKA_BOTTOM_RED_DOT_POSITION)
            }
        }

        ifDebugPref(DebugPrefs.RED_DOT) {
            fun traceBottomRedDotMutation(host: Any?, action: String, position: Int) {
                val map = getBottomRedDotMap(host)
                val callerNames = redDotCallerClassNames
                val stack = Throwable()
                    .stackTrace
                    .filter {
                        it.className.contains("PersonalCenterFragment") ||
                                it.className.contains("$HOOK_TARGET_PACKAGE.MainActivity") ||
                                callerNames.any { c -> it.className.startsWith(c) }
                    }
                    .take(12)
                    .joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
                YLog.debug("bottomRedDot action=$action position=$position map=$map stack=$stack")
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
    }

    private fun hideKakaPendingText(host: Any?) {
        val rootView = host as? View ?: return
        val textId = rootView.resources.getIdentifier(KAKA_PENDING_ID, "id", HOOK_TARGET_PACKAGE)
        if (textId != 0) collapseView(rootView.findViewById(textId))
    }

    private fun hasKakaRedDot(): Boolean {
        val context = getBaseContext() ?: return false
        val className = kakaManagerClassName ?: return false
        val getterName = kakaManagerGetterName ?: return false
        val redDotName = kakaRedDotMethodName ?: return false
        return runCatching {
            val managerClazz = context(this@PersonalCenterHooker) {
                className.toClassOrLog()
            } ?: return@runCatching false
            val manager = managerClazz.getMethod(getterName, Context::class.java)
                .invoke(null, context) ?: return@runCatching false
            manager.javaClass.getMethod(redDotName).invoke(manager) as? Boolean ?: false
        }.getOrDefault(false)
    }

    private fun getBaseContext(): Context? = runCatching {
        context(this@PersonalCenterHooker) {
            "com.huawei.hwcommonmodel.application.BaseApplication".toClassOrLog()
        }?.getMethod("getContext")
            ?.invoke(null) as? Context
    }.getOrNull()

    private fun getBottomRedDotMap(host: Any?): Map<*, *>? {
        val fieldName = bottomRedDotMapFieldName ?: return null
        if (host == null) return null
        return host.javaClass.getDeclaredField(fieldName)
            .apply { isAccessible = true }.get(host) as? Map<*, *>
    }

    private fun cancelBottomRedDot(host: Any?, position: Int) {
        if (host == null) return
        host.javaClass
            .getMethod(
                "cancelBottomRedDotVisible",
                Int::class.javaPrimitiveType ?: Int::class.javaObjectType
            )
            .invoke(host, position)
    }
}
