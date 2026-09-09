package moe.evil.hwhh.xposed.hooks

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import com.huawei.ui.commonui.popupview.PopViewList
import com.huawei.ui.commonui.titlebar.CustomTitleBar
import moe.evil.hwhh.xposed.utils.DexKitHooker
import moe.evil.hwhh.xposed.utils.HookApi
import moe.evil.hwhh.xposed.utils.wrapper.HostBridge
import moe.evil.hwhh.xposed.utils.wrapper.classOf
import moe.evil.hwhh.xposed.utils.wrapper.getOrNull
import moe.evil.hwhh.xposed.utils.wrapper.requireClass
import moe.evil.hwhh.xposed.utils.wrapper.requireConstructor
import moe.evil.hwhh.xposed.utils.wrapper.requireField
import moe.evil.hwhh.xposed.utils.wrapper.requireMethod
import moe.evil.hwhh.xposed.utils.wrapper.safeHook
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal interface HomeMenuApi : HookApi {
    fun addEntry(title: (Context) -> String, onSelect: (Activity) -> Unit)
}

internal object HomeMenuHooker : DexKitHooker<HomeMenuApi>() {
    private class Entry(val title: (Context) -> String, val onSelect: (Activity) -> Unit)

    private const val HOME_FRAGMENT_CLASS = "com.huawei.ui.homehealth.HomeFragment"
    private val entries = mutableListOf<Entry>()
    private var currentTitleBar: WeakReference<CustomTitleBar>? = null
    private val injectedAt = WeakHashMap<PopViewList, Int>()

    override val providedApi = object : HomeMenuApi {
        override fun addEntry(title: (Context) -> String, onSelect: (Activity) -> Unit) {
            entries += Entry(title, onSelect)
        }
    }

    override fun onHookWithDexKit(bridge: HostBridge) {
        val homeFragmentClazz = bridge.requireClass(HOME_FRAGMENT_CLASS)

        val titleBarField = bridge.requireField<CustomTitleBar>(
            label = "HomeFragment#titleBar",
            inPackage = HOME_FRAGMENT_CLASS.substringBeforeLast('.'),
        ) {
            declaredClass(homeFragmentClazz)
        }

        homeFragmentClazz.requireMethod {
            name = "onActivityCreated"
            parameters(classOf<Bundle>())
        }.safeHook {
            after {
                currentTitleBar = titleBarField.getOrNull(instanceOrNull)?.let { WeakReference(it) }
            }
        }

        val setClickListener = bridge.requireMethod<Unit>(
            label = "PopViewList#setClickListener",
            inPackage = "com.huawei.ui.commonui.popupview",
        ) {
            declaredClass(classOf<PopViewList>())
            paramTypes(classOf<PopViewList.PopViewClickListener>())
        }

        classOf<PopViewList>().requireConstructor {
            parameters(classOf<Context>(), classOf<View>(), classOf<ArrayList<String>>())
        }.safeHook {
            before {
                if (entries.isEmpty()) return@before
                val titleBar = currentTitleBar?.get() ?: return@before
                if (args(1).cast<View?>() !== titleBar) return@before
                val activity = titleBar.context as? Activity ?: return@before
                val items = args(2).cast<ArrayList<String>?>() ?: return@before
                injectedAt[instance<PopViewList>()] = items.size
                entries.mapTo(items) { it.title(activity) }
            }
        }

        setClickListener.safeHook {
            before {
                val base = injectedAt.remove(instance<PopViewList>()) ?: return@before
                val original = args(0).cast<PopViewList.PopViewClickListener?>() ?: return@before
                val injected = entries.toList()
                args(0).set(object : PopViewList.PopViewClickListener {
                    override fun setOnClick(position: Int) {
                        val entry = injected.getOrNull(position - base)
                            ?: return original.setOnClick(position)
                        (currentTitleBar?.get()?.context as? Activity)?.let(entry.onSelect)
                    }
                })
            }
        }
    }
}
