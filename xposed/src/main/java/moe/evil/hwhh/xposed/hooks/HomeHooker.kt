package moe.evil.hwhh.xposed.hooks

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import moe.evil.hwhh.xposed.HOOK_TARGET_PACKAGE
import moe.evil.hwhh.xposed.utils.DexKitHooker
import moe.evil.hwhh.xposed.utils.collapseView
import moe.evil.hwhh.xposed.utils.firstMethodOrNullLogged
import moe.evil.hwhh.xposed.utils.safeHook
import moe.evil.hwhh.xposed.utils.toClassOrLog
import moe.evil.hwhh.xposed.utils.tryHookWithDexKit

object HomeHooker : DexKitHooker() {

    override fun onHook() = tryHookWithDexKit { bridge ->
        // bottom recommend card classes
        val operationCardDataClazz = context(this@HomeHooker) {
            "com.huawei.ui.homehealth.operationcard.OperationCardData".toClassOrLog()
        } ?: return@tryHookWithDexKit
        val operationCardViewHolderClazz = context(this@HomeHooker) {
            "com.huawei.ui.homehealth.operationcard.OperationCardViewHolder".toClassOrLog()
        } ?: return@tryHookWithDexKit
        // function menu card classes
        val functionMenuCardDataClazz = context(this@HomeHooker) {
            "com.huawei.ui.homehealth.FunctionMenuCardData".toClassOrLog()
        } ?: return@tryHookWithDexKit
        val functionMenuViewHolderClazz = context(this@HomeHooker) {
            $$"com.huawei.ui.homehealth.FunctionMenuCardData$FunctionMenuViewHolder".toClassOrLog()
        } ?: return@tryHookWithDexKit
        // daily moment card classes
        val dailyMomentCardAdapterClazz = context(this@HomeHooker) {
            "com.huawei.health.functionsetcard.dailymoment.DailyMomentCardAdapter".toClassOrLog()
        } ?: return@tryHookWithDexKit
        val functionSetViewAdapterClazz = context(this@HomeHooker) {
            "com.huawei.health.functionsetcard.FunctionSetViewAdapter".toClassOrLog()
        } ?: return@tryHookWithDexKit
        val recyclerViewHolderClazz = context(this@HomeHooker) {
            $$"androidx.recyclerview.widget.RecyclerView$ViewHolder".toClassOrLog()
        } ?: return@tryHookWithDexKit

        val ocvhVisibilityName = bridge.findMethod {
            searchPackages("com.huawei.ui.homehealth.operationcard")
            matcher {
                declaredClass = "com.huawei.ui.homehealth.operationcard.OperationCardViewHolder"
                paramTypes("int")
                returnType = "void"
            }
        }.single().name

        val fmvhVisibilityName = bridge.findMethod {
            searchPackages("com.huawei.ui.homehealth")
            matcher {
                declaredClass =
                    $$"com.huawei.ui.homehealth.FunctionMenuCardData$FunctionMenuViewHolder"
                paramTypes("int")
                returnType = "void"
            }
        }.single().name

        val dmcaCreateHolderName = bridge.findMethod {
            searchPackages("com.huawei.health.functionsetcard.dailymoment")
            matcher {
                declaredClass = "com.huawei.health.functionsetcard.dailymoment.DailyMomentCardAdapter"
                paramTypes("android.view.ViewGroup")
            }
        }.single().name

        // very bottom ads
        operationCardDataClazz.firstMethodOrNullLogged {
            name = "getCardViewHolder"
            parameters(ViewGroup::class, LayoutInflater::class)
        }?.safeHook {
            after {
                collapseView(extractItemView(result))
            }
        }

        // bottom recommend card visibility setter (dexkit-resolved)
        operationCardViewHolderClazz.firstMethodOrNullLogged {
            name = ocvhVisibilityName
            parameters(Int::class.javaPrimitiveType ?: Int::class.javaObjectType)
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
            parameters(ViewGroup::class, LayoutInflater::class)
        }?.safeHook {
            after {
                collapseView(extractItemView(result))
            }
        }

        // function menu card visibility setter (dexkit-resolved)
        functionMenuViewHolderClazz.firstMethodOrNullLogged {
            name = fmvhVisibilityName
            parameters(Int::class.javaPrimitiveType ?: Int::class.javaObjectType)
        }?.safeHook {
            before {
                args(0).set(View.GONE)
            }
            after {
                collapseView(extractItemView(instanceOrNull))
            }
        }

        // ads in health data — daily moment card create holder (dexkit-resolved)
        dailyMomentCardAdapterClazz.firstMethodOrNullLogged {
            name = dmcaCreateHolderName
            parameters(ViewGroup::class)
        }?.safeHook {
            after {
                collapseDailyMomentCard(result)
            }
        }

        functionSetViewAdapterClazz.firstMethodOrNullLogged {
            name = "onBindViewHolder"
            parameters(
                recyclerViewHolderClazz,
                Int::class.javaPrimitiveType ?: Int::class.javaObjectType
            )
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
        val dailyMomentCardId = itemView.resources.getIdentifier("daily_moment_health_card", "id", HOOK_TARGET_PACKAGE)
        if (dailyMomentCardId != 0) {
            itemView.findViewById<View>(dailyMomentCardId)?.let { collapseView(it) }
        }
        collapseView(itemView)
    }

    private fun extractItemView(any: Any?): View = when (any) {
        null -> throw IllegalStateException("extractItemView: argument is null")
        is View -> any
        else -> runCatching {
            any.javaClass.getField("itemView").get(any) as? View
        }.recoverCatching {
            any.javaClass.getDeclaredField("itemView")
                .apply { isAccessible = true }
                .get(any) as? View
        }.getOrElse {
            throw IllegalStateException(
                "extractItemView: failed to find itemView in ${any.javaClass.name}",
                it
            )
        } ?: throw IllegalStateException(
            "extractItemView: itemView in ${any.javaClass.name} is null or not a View"
        )
    }

}
