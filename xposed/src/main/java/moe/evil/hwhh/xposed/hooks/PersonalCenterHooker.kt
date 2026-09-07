package moe.evil.hwhh.xposed.hooks

import android.content.Context
import android.view.View
import com.huawei.ui.main.stories.userprofile.activity.PersonalCenterFragment
import moe.evil.hwhh.shared.DebugToggle
import moe.evil.hwhh.shared.HOOK_TARGET_PACKAGE
import moe.evil.hwhh.shared.HookRoot
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.xposed.utils.DexKitBaseHooker
import moe.evil.hwhh.xposed.utils.asResIdOrNull
import moe.evil.hwhh.xposed.utils.collapseView
import moe.evil.hwhh.xposed.utils.ifDebugPref
import moe.evil.hwhh.xposed.utils.wrapper.HostBridge
import moe.evil.hwhh.xposed.utils.wrapper.HostField
import moe.evil.hwhh.xposed.utils.wrapper.HostMethod
import moe.evil.hwhh.xposed.utils.wrapper.classOf
import moe.evil.hwhh.xposed.utils.wrapper.getOrNull
import moe.evil.hwhh.xposed.utils.wrapper.invokeOrNull
import moe.evil.hwhh.xposed.utils.wrapper.method
import moe.evil.hwhh.xposed.utils.wrapper.orWarnEmpty
import moe.evil.hwhh.xposed.utils.wrapper.requireClass
import moe.evil.hwhh.xposed.utils.wrapper.requireField
import moe.evil.hwhh.xposed.utils.wrapper.requireMethod
import moe.evil.hwhh.xposed.utils.wrapper.safeHook
import java.lang.reflect.Modifier

@HookRoot(order = 2)
object PersonalCenterHooker : DexKitBaseHooker() {
    private const val KAKA_BOTTOM_RED_DOT_POSITION = 4
    private const val KAKA_CHECK_IN_BOTTOM_RED_DOT_POSITION = 1024
    private const val MESSAGE_BOTTOM_RED_DOT_POSITION = 1
    private const val KAKA_PENDING_ID = "kaka_to_be_collected_text"
    private const val SCROLL_PACKAGE = "com.huawei.ui.main.stories.userprofile.scroll"
    private const val ACTIVITY_PACKAGE = "com.huawei.ui.main.stories.userprofile.activity"
    private const val CUSTOM_HEAD_VIEW = "$SCROLL_PACKAGE.CustomHeadView"
    private const val PERSONAL_CENTER_FRAGMENT = "$ACTIVITY_PACKAGE.PersonalCenterFragment"
    private const val BASE_APPLICATION = "com.huawei.hwcommonmodel.application.BaseApplication"

    private class Lookups(
        val kakaManagerOf: HostMethod<Any>,
        val kakaRedDot: HostMethod<Boolean>,
        val bottomRedDotMap: HostField<Map<*, *>>,
        val redDotCallerClassNames: Set<String>,
        val appContextOf: HostMethod<Context>?,
    )

    private val log = HLog.of<PersonalCenterHooker>()

    private lateinit var lookups: Lookups

    @Volatile
    private var kakaPendingId: Int? = null

    override fun onHookWithDexKit(bridge: HostBridge) {
        val customHeadViewClazz = bridge.requireClass(CUSTOM_HEAD_VIEW)
        val personalCenterFragmentClazz = bridge.requireClass(PERSONAL_CENTER_FRAGMENT)

        val kakaRedDot = bridge.requireMethod<Boolean>("KakaManager#getKakaTaskRedDot") {
            usingStrings = listOf("getKakaTaskRedDot enter")
            paramCount(0)
        }
        val kakaManagerOf = bridge.requireMethod<Any>("KakaManager#instance") {
            declaredClass(kakaRedDot.owner)
            returnType(kakaRedDot.owner)
            paramTypes(classOf<Context>())
        }
        val bottomRedDotMap = bridge.requireField<Map<*, *>>(
            label = "PersonalCenterFragment#bottomRedDotMap",
            inPackage = ACTIVITY_PACKAGE,
        ) {
            declaredClass(personalCenterFragmentClazz)
        }

        val chvWithFindView = bridge.findMethod {
            searchPackages(SCROLL_PACKAGE)
            matcher {
                declaredClass(customHeadViewClazz)
                modifiers = Modifier.PUBLIC
                paramTypes(classOf<Int>())
                returnType(Void.TYPE)
                addInvoke { name = "setVisibility" }
                addInvoke { name = "findViewById" }
            }
        }.mapTo(HashSet()) { it.name }.orWarnEmpty("CustomHeadView#findViewById setters")

        lookups = Lookups(
            kakaManagerOf = kakaManagerOf,
            kakaRedDot = kakaRedDot,
            bottomRedDotMap = bottomRedDotMap,
            redDotCallerClassNames = bridge.findMethod {
                searchPackages("com.huawei.ui.main.stories.userprofile")
                matcher {
                    addInvoke {
                        declaredClass = PERSONAL_CENTER_FRAGMENT
                        name = "setBottomRedDotVisibility"
                    }
                }
            }.mapNotNullTo(HashSet()) {
                it.className.takeIf { name -> name != PERSONAL_CENTER_FRAGMENT }
            },
            appContextOf = bridge.method<Context>("BaseApplication#getContext") {
                declaredClass = BASE_APPLICATION
                name = "getContext"
                paramTypes()
            },
        )

        // kaka visibility setter on CustomHeadView (dexkit-resolved)
        bridge.method<Unit>(
            label = "CustomHeadView#kakaVisibility",
            inPackage = SCROLL_PACKAGE,
            pick = { singleOrNull { it.name !in chvWithFindView } },
        ) {
            declaredClass(customHeadViewClazz)
            modifiers = Modifier.PUBLIC
            paramTypes(classOf<Int>())
            addInvoke { name = "setVisibility" }
        }?.safeHook {
            before {
                args(0).set(View.GONE)
            }
            after {
                hideKakaPendingText(instanceOrNull)
            }
        }

        // kaka text setter on CustomHeadView (dexkit-resolved)
        bridge.method<Unit>("CustomHeadView#kakaText", inPackage = SCROLL_PACKAGE) {
            declaredClass(customHeadViewClazz)
            paramTypes(classOf<String>())
            addInvoke { name = "getText" }
        }?.safeHook {
            before {
                hideKakaPendingText(instanceOrNull)
            }
            after {
                hideKakaPendingText(instanceOrNull)
            }
        }

        // unread message count setter (dexkit-resolved)
        bridge.method<Any>("PersonalCenterFragment#setUnreadMessageNum", ACTIVITY_PACKAGE) {
            declaredClass(personalCenterFragmentClazz)
            paramTypes(classOf<Int>())
            usingStrings = listOf("Enter setUnreadMessageNum unreadMessageNum:")
        }?.safeHook {
            before {
                val original = args(0).int()
                val fixed = if (hasKakaRedDot()) (original - 1).coerceAtLeast(0) else original
                log.debug { "Set unread message num: original=$original, fixed=$fixed" }
                args(0).set(fixed)
            }
            after {
                if (args(0).int() == 0) {
                    cancelBottomRedDot(instanceOrNull, MESSAGE_BOTTOM_RED_DOT_POSITION)
                }
            }
        }

        // kaka bottom red dot updater (dexkit-resolved)
        bridge.method<Unit>("PersonalCenterFragment#kakaRedDotUpdater", ACTIVITY_PACKAGE) {
            declaredClass(personalCenterFragmentClazz)
            addInvoke { name = "setBottomRedDotVisibility" }
            addInvoke { name = "cancelBottomRedDotVisible" }
            addInvoke {
                declaredClass(kakaRedDot.owner)
                name = kakaRedDot.name
            }
        }?.safeHook {
            after {
                cancelBottomRedDot(instanceOrNull, KAKA_BOTTOM_RED_DOT_POSITION)
            }
        }

        // kaka check-in bottom red dot (dexkit-resolved)
        bridge.method<Unit>("PersonalCenterFragment#kakaCheckInRedDot", ACTIVITY_PACKAGE) {
            declaredClass(personalCenterFragmentClazz)
            paramTypes(classOf<Boolean>())
            usingStrings = listOf("updateKakaCheckRed")
        }?.safeHook {
            after {
                cancelBottomRedDot(instanceOrNull, KAKA_CHECK_IN_BOTTOM_RED_DOT_POSITION)
            }
        }

        ifDebugPref(DebugToggle.RED_DOT) {
            fun traceBottomRedDotMutation(host: Any?, action: String, position: Int) {
                val map = getBottomRedDotMap(host)
                val callerNames = lookups.redDotCallerClassNames
                val stack = Throwable()
                    .stackTrace
                    .filter {
                        it.className.contains("PersonalCenterFragment") ||
                                it.className.contains("$HOOK_TARGET_PACKAGE.MainActivity") ||
                                callerNames.any { c -> it.className.startsWith(c) }
                    }
                    .take(12)
                    .joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
                log.debug { "Bottom red dot action=$action position=$position map=$map stack=$stack" }
            }

            personalCenterFragmentClazz.method {
                name = "setBottomRedDotVisibility"
                parameters(classOf<Int>())
            }?.safeHook {
                before {
                    traceBottomRedDotMutation(instanceOrNull, "set", args(0).int())
                }
                after {
                    traceBottomRedDotMutation(instanceOrNull, "set-after", args(0).int())
                }
            }

            personalCenterFragmentClazz.method {
                name = "cancelBottomRedDotVisible"
                parameters(classOf<Int>())
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
        val pendingId = kakaPendingId ?: rootView.resources
            .getIdentifier(KAKA_PENDING_ID, "id", HOOK_TARGET_PACKAGE)
            .also { kakaPendingId = it }
        pendingId.asResIdOrNull()
            ?.let { rootView.findViewById<View>(it) }
            ?.let(::collapseView)
    }

    private fun hasKakaRedDot(): Boolean {
        val context = lookups.appContextOf?.invokeOrNull(null) ?: return false
        val manager = lookups.kakaManagerOf.invokeOrNull(null, context) ?: return false
        return lookups.kakaRedDot.invokeOrNull(manager) == true
    }

    private fun getBottomRedDotMap(host: Any?) = host?.let { lookups.bottomRedDotMap.getOrNull(it) }

    private fun cancelBottomRedDot(host: Any?, position: Int) {
        (host as? PersonalCenterFragment)?.cancelBottomRedDotVisible(position)
    }
}
