package moe.evil.hwhh.xposed.hooks

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import moe.evil.hwhh.shared.HookRoot
import moe.evil.hwhh.xposed.utils.DexKitBaseHooker
import moe.evil.hwhh.xposed.utils.asResIdOrNull
import moe.evil.hwhh.xposed.utils.collapseView
import moe.evil.hwhh.xposed.utils.hostViewId
import moe.evil.hwhh.xposed.utils.wrapper.HostBridge
import moe.evil.hwhh.xposed.utils.wrapper.HostField
import moe.evil.hwhh.xposed.utils.wrapper.classOf
import moe.evil.hwhh.xposed.utils.wrapper.getOrNull
import moe.evil.hwhh.xposed.utils.wrapper.method
import moe.evil.hwhh.xposed.utils.wrapper.requireClass
import moe.evil.hwhh.xposed.utils.wrapper.requireField
import moe.evil.hwhh.xposed.utils.wrapper.safeHook
import java.util.concurrent.ConcurrentHashMap

@HookRoot(order = 0)
object HomeHooker : DexKitBaseHooker() {
    private const val DAILY_MOMENT_CARD_ID = "daily_moment_health_card"
    private val itemViewFields = ConcurrentHashMap<Class<*>, HostField<View>>()

    @Volatile
    private var dailyMomentCardId: Int? = null

    override fun onHookWithDexKit(bridge: HostBridge) {
        val operationCardDataClazz =
            bridge.requireClass("com.huawei.ui.homehealth.operationcard.OperationCardData")
        val operationCardViewHolderClazz =
            bridge.requireClass("com.huawei.ui.homehealth.operationcard.OperationCardViewHolder")
        val functionMenuCardDataClazz =
            bridge.requireClass("com.huawei.ui.homehealth.FunctionMenuCardData")
        val functionMenuViewHolderClazz = bridge.requireClass(
            $$"com.huawei.ui.homehealth.FunctionMenuCardData$FunctionMenuViewHolder",
        )
        val dailyMomentCardAdapterClazz = bridge.requireClass(
            "com.huawei.health.functionsetcard.dailymoment.DailyMomentCardAdapter",
        )
        val functionSetViewAdapterClazz =
            bridge.requireClass("com.huawei.health.functionsetcard.FunctionSetViewAdapter")
        val recyclerViewHolderClazz =
            bridge.requireClass($$"androidx.recyclerview.widget.RecyclerView$ViewHolder")

        // very bottom ads
        operationCardDataClazz.method {
            name = "getCardViewHolder"
            parameters(classOf<ViewGroup>(), classOf<LayoutInflater>())
        }?.safeHook {
            after {
                collapseView(extractItemView(result))
            }
        }

        // bottom recommend card visibility setter (dexkit-resolved)
        bridge.method<Unit>(
            label = "OperationCardViewHolder#visibility",
            inPackage = "com.huawei.ui.homehealth.operationcard",
        ) {
            declaredClass(operationCardViewHolderClazz)
            paramTypes(classOf<Int>())
        }?.safeHook {
            before {
                args(0).set(View.GONE)
            }
            after {
                collapseView(extractItemView(instanceOrNull))
            }
        }

        // ads between three-circle data and health data
        functionMenuCardDataClazz.method {
            name = "getCardViewHolder"
            parameters(classOf<ViewGroup>(), classOf<LayoutInflater>())
        }?.safeHook {
            after {
                collapseView(extractItemView(result))
            }
        }

        // function menu card visibility setter (dexkit-resolved)
        bridge.method<Unit>(
            label = "FunctionMenuViewHolder#visibility",
            inPackage = "com.huawei.ui.homehealth",
        ) {
            declaredClass(functionMenuViewHolderClazz)
            paramTypes(classOf<Int>())
        }?.safeHook {
            before {
                args(0).set(View.GONE)
            }
            after {
                collapseView(extractItemView(instanceOrNull))
            }
        }

        // ads in health data — daily moment card create holder (dexkit-resolved)
        bridge.method<Any>(
            label = "DailyMomentCardAdapter#createHolder",
            inPackage = "com.huawei.health.functionsetcard.dailymoment",
        ) {
            declaredClass(dailyMomentCardAdapterClazz)
            paramTypes(classOf<ViewGroup>())
        }?.safeHook {
            after {
                collapseDailyMomentCard(result)
            }
        }

        functionSetViewAdapterClazz.method {
            name = "onBindViewHolder"
            parameters(recyclerViewHolderClazz, classOf<Int>())
        }?.safeHook {
            after {
                if (args(1).int() == 1) {
                    collapseDailyMomentCard(args(0).any())
                }
            }
        }
    }

    private fun collapseDailyMomentCard(holder: Any?) {
        val itemView = extractItemView(holder)
        val cardId = dailyMomentCardId ?: itemView.resources
            .hostViewId(DAILY_MOMENT_CARD_ID)
            .also { dailyMomentCardId = it }
        cardId.asResIdOrNull()?.let { itemView.findViewById<View>(it) }?.let(::collapseView)
        collapseView(itemView)
    }

    private fun extractItemView(any: Any?): View {
        checkNotNull(any) { "extractItemView: argument is null" }
        if (any is View) return any
        val field = itemViewFields.getOrPut(any.javaClass) {
            any.javaClass.requireField(classOf<View>()) {
                name = "itemView"
                superclass()
            }
        }
        return checkNotNull(field.getOrNull(any)) {
            "extractItemView: itemView is null in ${any.javaClass.name}"
        }
    }

}
