package moe.evil.hwhh.xposed.hooks

import android.content.Context
import android.view.View
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.huawei.hwcommonmodel.application.BaseApplication
import com.huawei.ui.main.stories.userprofile.activity.PersonalCenterFragment
import moe.evil.hwhh.xposed.DebugPrefs
import moe.evil.hwhh.xposed.HOOK_TARGET_PACKAGE
import moe.evil.hwhh.xposed.utils.DexKitHooker
import moe.evil.hwhh.xposed.utils.HLog
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

    private data class Lookups(
        val kakaManagerClassName: String,
        val kakaManagerGetterName: String,
        val kakaRedDotMethodName: String,
        val bottomRedDotMapFieldName: String,
        val redDotCallerClassNames: Set<String>,
    )

    private val log = HLog.of<PersonalCenterHooker>()

    private var lookups: Lookups? = null

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
        val kakaRedDotMethodName = kakaRedDotMethodData.name
        val kakaManagerGetterName = bridge.findMethod {
            matcher {
                declaredClass = kmClassName
                paramTypes("android.content.Context")
                returnType = kmClassName
            }
        }.single().name

        val chvClassName = "com.huawei.ui.main.stories.userprofile.scroll.CustomHeadView"

        val chvKakaVisibilityName = bridge.findMethod {
            searchPackages("com.huawei.ui.main.stories.userprofile.scroll")
            matcher {
                declaredClass = chvClassName
                modifiers = java.lang.reflect.Modifier.PUBLIC
                paramTypes("int")
                returnType = "void"
                addInvoke { name = "setVisibility" }
            }
        }.let { all ->
            val withFindView = bridge.findMethod {
                searchPackages("com.huawei.ui.main.stories.userprofile.scroll")
                matcher {
                    declaredClass = chvClassName
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
                declaredClass = chvClassName
                paramTypes("java.lang.String")
                returnType = "void"
                addInvoke { name = "getText" }
            }
        }.single().name

        val pcfClassName = "com.huawei.ui.main.stories.userprofile.activity.PersonalCenterFragment"

        val pcfSetUnreadMsgName = bridge.findMethod {
            searchPackages("com.huawei.ui.main.stories.userprofile.activity")
            matcher {
                declaredClass = pcfClassName
                usingStrings = listOf("Enter setUnreadMessageNum unreadMessageNum:")
            }
        }.single().name

        val pcfKakaRedDotUpdaterName = bridge.findMethod {
            searchPackages("com.huawei.ui.main.stories.userprofile.activity")
            matcher {
                declaredClass = pcfClassName
                returnType = "void"
                addInvoke { name = "setBottomRedDotVisibility" }
                addInvoke { name = "cancelBottomRedDotVisible" }
                addInvoke {
                    declaredClass = kmClassName
                    name = kakaRedDotMethodData.name
                }
            }
        }.single().name

        val bottomRedDotMapFieldName = bridge.findField {
            searchPackages("com.huawei.ui.main.stories.userprofile.activity")
            matcher {
                declaredClass = pcfClassName
                type = "java.util.Map"
            }
        }.single().name

        val redDotCallerClassNames = bridge.findMethod {
            searchPackages("com.huawei.ui.main.stories.userprofile")
            matcher {
                addInvoke {
                    declaredClass = pcfClassName
                    name = "setBottomRedDotVisibility"
                }
            }
        }.map { it.className }.filter { it != pcfClassName }.toSet()

        lookups = Lookups(
            kakaManagerClassName = kmClassName,
            kakaManagerGetterName = kakaManagerGetterName,
            kakaRedDotMethodName = kakaRedDotMethodName,
            bottomRedDotMapFieldName = bottomRedDotMapFieldName,
            redDotCallerClassNames = redDotCallerClassNames,
        )

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
                log.debug { "PersonalCenterFragment#b: original=$original, fixed=$fixedCount" }
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
                val callerNames = lookups?.redDotCallerClassNames.orEmpty()
                val stack = Throwable()
                    .stackTrace
                    .filter {
                        it.className.contains("PersonalCenterFragment") ||
                                it.className.contains("$HOOK_TARGET_PACKAGE.MainActivity") ||
                                callerNames.any { c -> it.className.startsWith(c) }
                    }
                    .take(12)
                    .joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
                log.debug { "bottomRedDot action=$action position=$position map=$map stack=$stack" }
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
        val l = lookups ?: return false
        val context = BaseApplication.getContext() ?: return false
        val managerClazz = context(this@PersonalCenterHooker) {
            l.kakaManagerClassName.toClassOrLog()
        } ?: return false
        val manager = managerClazz.resolve().optional(silent = true).firstMethodOrNull {
            name = l.kakaManagerGetterName
            parameters(Context::class)
        }?.invokeQuietly(context) ?: return false
        return manager.asResolver().optional(silent = true).firstMethodOrNull {
            name = l.kakaRedDotMethodName
            emptyParameters()
        }?.invokeQuietly() as? Boolean ?: false
    }

    private fun getBottomRedDotMap(host: Any?): Map<*, *>? {
        if (host == null) return null
        val fieldName = lookups?.bottomRedDotMapFieldName ?: return null
        return host.asResolver().optional(silent = true).firstFieldOrNull {
            name = fieldName
        }?.getQuietly() as? Map<*, *>
    }

    private fun cancelBottomRedDot(host: Any?, position: Int) {
        (host as? PersonalCenterFragment)?.cancelBottomRedDotVisible(position)
    }
}
