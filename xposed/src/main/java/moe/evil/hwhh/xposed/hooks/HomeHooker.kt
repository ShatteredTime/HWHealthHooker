package moe.evil.hwhh.xposed.hooks

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.highcapable.kavaref.extension.classOf
import moe.evil.hwhh.kdxref.HostBridge
import moe.evil.hwhh.kdxref.HostField
import moe.evil.hwhh.kdxref.firstFieldOrNullLogged
import moe.evil.hwhh.kdxref.firstMethodOrNullLogged
import moe.evil.hwhh.kdxref.hostMethod
import moe.evil.hwhh.kdxref.safeHook
import moe.evil.hwhh.kdxref.toClassOrLog
import moe.evil.hwhh.shared.HOOK_TARGET_PACKAGE
import moe.evil.hwhh.shared.HookRoot
import moe.evil.hwhh.xposed.utils.DexKitBaseHooker
import moe.evil.hwhh.xposed.utils.asResIdOrNull
import moe.evil.hwhh.xposed.utils.collapseView
import java.util.concurrent.ConcurrentHashMap

@HookRoot(order = 0)
object HomeHooker : DexKitBaseHooker() {
    private const val DAILY_MOMENT_CARD_ID = "daily_moment_health_card"
    private val itemViewFields = ConcurrentHashMap<Class<*>, HostField<View>>()

    @Volatile
    private var dailyMomentCardId: Int? = null

    override fun onHookWithDexKit(bridge: HostBridge) {
        // bottom recommend card classes
        val operationCardDataClazz = context(this@HomeHooker) {
            "com.huawei.ui.homehealth.operationcard.OperationCardData".toClassOrLog()
        } ?: return
        val operationCardViewHolderClazz = context(this@HomeHooker) {
            "com.huawei.ui.homehealth.operationcard.OperationCardViewHolder".toClassOrLog()
        } ?: return
        // function menu card classes
        val functionMenuCardDataClazz = context(this@HomeHooker) {
            "com.huawei.ui.homehealth.FunctionMenuCardData".toClassOrLog()
        } ?: return
        val functionMenuViewHolderClazz = context(this@HomeHooker) {
            $$"com.huawei.ui.homehealth.FunctionMenuCardData$FunctionMenuViewHolder".toClassOrLog()
        } ?: return
        // daily moment card classes
        val dailyMomentCardAdapterClazz = context(this@HomeHooker) {
            "com.huawei.health.functionsetcard.dailymoment.DailyMomentCardAdapter".toClassOrLog()
        } ?: return
        val functionSetViewAdapterClazz = context(this@HomeHooker) {
            "com.huawei.health.functionsetcard.FunctionSetViewAdapter".toClassOrLog()
        } ?: return
        val recyclerViewHolderClazz = context(this@HomeHooker) {
            $$"androidx.recyclerview.widget.RecyclerView$ViewHolder".toClassOrLog()
        } ?: return

        // very bottom ads
        operationCardDataClazz.firstMethodOrNullLogged {
            name = "getCardViewHolder"
            parameters(classOf<ViewGroup>(), classOf<LayoutInflater>())
        }?.safeHook {
            after {
                collapseView(extractItemView(result))
            }
        }

        // bottom recommend card visibility setter (dexkit-resolved)
        hostMethod<Unit>(
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
        functionMenuCardDataClazz.firstMethodOrNullLogged {
            name = "getCardViewHolder"
            parameters(classOf<ViewGroup>(), classOf<LayoutInflater>())
        }?.safeHook {
            after {
                collapseView(extractItemView(result))
            }
        }

        // function menu card visibility setter (dexkit-resolved)
        hostMethod<Unit>(
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
        hostMethod<Any>(
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

        functionSetViewAdapterClazz.firstMethodOrNullLogged {
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
            .getIdentifier(DAILY_MOMENT_CARD_ID, "id", HOOK_TARGET_PACKAGE)
            .also { dailyMomentCardId = it }
        cardId.asResIdOrNull()?.let { itemView.findViewById<View>(it) }?.let(::collapseView)
        collapseView(itemView)
    }

    private fun extractItemView(any: Any?): View {
        checkNotNull(any) { "extractItemView: argument is null" }
        if (any is View) return any
        val field = itemViewFields.getOrPut(any.javaClass) {
            checkNotNull(
                any.javaClass.firstFieldOrNullLogged(classOf<View>()) {
                    name = "itemView"
                    superclass()
                }
            ) { "extractItemView: itemView not found in ${any.javaClass.name}" }
        }
        return checkNotNull(field.on(any)) {
            "extractItemView: itemView is null in ${any.javaClass.name}"
        }
    }

}
