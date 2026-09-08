package moe.evil.hwhh.xposed.hooks

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.format.Formatter
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.huawei.ui.commonui.divider.HealthDivider
import moe.evil.hwhh.shared.HookRoot
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.xposed.R
import moe.evil.hwhh.xposed.sportdata.exporter.exportDir
import moe.evil.hwhh.xposed.utils.DexKitBaseHooker
import moe.evil.hwhh.xposed.utils.asResIdOrNull
import moe.evil.hwhh.xposed.utils.hostViewId
import moe.evil.hwhh.xposed.utils.matchWidth
import moe.evil.hwhh.xposed.utils.moduleString
import moe.evil.hwhh.xposed.utils.toast
import moe.evil.hwhh.xposed.utils.wrapper.HostBridge
import moe.evil.hwhh.xposed.utils.wrapper.HostMethod
import moe.evil.hwhh.xposed.utils.wrapper.classOf
import moe.evil.hwhh.xposed.utils.wrapper.invokeOrNull
import moe.evil.hwhh.xposed.utils.wrapper.method
import moe.evil.hwhh.xposed.utils.wrapper.requireClass
import moe.evil.hwhh.xposed.utils.wrapper.requireMethod
import moe.evil.hwhh.xposed.utils.wrapper.safeHook
import java.io.File
import kotlin.concurrent.thread

@HookRoot(order = 6)
object ModuleCacheHooker : DexKitBaseHooker() {
    private const val CLEAR_CACHE_ACTIVITY =
        "com.huawei.ui.main.stories.settings.activity.ClearDataCacheActivity"
    private const val DONOR_ROW = "clear_other_data_cache_layout"
    private const val DONOR_LABEL = "hw_show_clear_other_textview"
    private const val DONOR_VALUE = "hw_show_clear_other_text"
    private const val DONOR_ARROW = "hw_clear_other_data_right_arrow"

    private class Donor(
        val container: ViewGroup,
        val row: LinearLayout,
        val label: TextView,
        val tail: RelativeLayout,
        val value: TextView,
        val arrow: ImageView,
    )

    private val log = HLog.of<ModuleCacheHooker>()

    private val commonUi by require { CommonUIHooker }

    @Volatile
    private var formatSize: HostMethod<String>? = null

    override fun onHookWithDexKit(bridge: HostBridge) {
        formatSize = bridge.method<String>("Utils#formatFileSize") {
            usingStrings = listOf("formatFileSize context is null")
            paramTypes(classOf<Context>(), classOf<Long>())
        }

        bridge.requireClass(CLEAR_CACHE_ACTIVITY)
            .requireMethod {
                name = "onCreate"
                parameters(classOf<Bundle>())
            }.safeHook {
                after {
                    val activity = instanceOrNull as? Activity ?: return@after
                    val donor = activity.donor()
                    val (row, value) = activity.mimicRow(donor)
                    value.text = activity.formatBytes(0L)
                    row.setOnClickListener {
                        commonUi.createNoTitleCustomAlertDialog(
                            activity = activity,
                            message = activity.moduleString(R.string.hwhh_cache_clear_message),
                            positive = DialogButton(
                                activity.moduleString(R.string.hwhh_cache_clear_confirm),
                            ) {
                                activity.syncCacheRow(row, value) {
                                    activity.toast(
                                        activity.moduleString(
                                            if (deleteRecursively()) R.string.hwhh_cache_cleared
                                            else R.string.hwhh_cache_partially_cleared,
                                        ),
                                    )
                                }
                            },
                            negative = DialogButton(activity.moduleString(R.string.hwhh_cancel)),
                        ).gracefulShow()
                    }
                    val at = donor.container.indexOfChild(donor.row) + 1
                    donor.container.addView(
                        HealthDivider(activity).apply {
                            layoutParams = donor.container.getChildAt(at - 2)
                                ?.let { LinearLayout.LayoutParams(it.linearParams()) }
                                ?: matchWidth()
                        },
                        at,
                    )
                    donor.container.addView(row, at + 1)
                    activity.syncCacheRow(row, value)
                    log.debug { "Module cache row added to $CLEAR_CACHE_ACTIVITY" }
                }
            }
    }

    private fun Activity.syncCacheRow(
        row: View,
        value: TextView,
        mutate: File.() -> Unit = {},
    ) = thread {
        val bytes = applicationContext.exportDir()
            .apply(mutate)
            .walkTopDown()
            .filter(File::isFile)
            .sumOf(File::length)
        val text = formatBytes(bytes)
        runOnUiThread {
            value.text = text
            row.isClickable = bytes > 0L
        }
    }

    private fun Activity.formatBytes(bytes: Long) =
        formatSize?.invokeOrNull(null, this, bytes) ?: Formatter.formatFileSize(this, bytes)

    private fun Activity.donor(): Donor {
        fun viewId(name: String) = checkNotNull(resources.hostViewId(name).asResIdOrNull()) { name }
        val row = checkNotNull(findViewById<LinearLayout>(viewId(DONOR_ROW))) { DONOR_ROW }
        val value = checkNotNull(findViewById<TextView>(viewId(DONOR_VALUE))) { DONOR_VALUE }
        return Donor(
            container = checkNotNull(row.parent as? ViewGroup) { "$DONOR_ROW has no parent" },
            row = row,
            label = checkNotNull(findViewById(viewId(DONOR_LABEL))) { DONOR_LABEL },
            tail = checkNotNull(value.parent as? RelativeLayout) { "$DONOR_VALUE has no parent" },
            value = value,
            arrow = checkNotNull(findViewById(viewId(DONOR_ARROW))) { DONOR_ARROW },
        )
    }

    private fun Activity.mimicRow(donor: Donor): Pair<LinearLayout, TextView> {
        val arrow = ImageView(this).apply {
            id = View.generateViewId()
            background = donor.arrow.background?.constantState?.newDrawable()
            layoutParams = RelativeLayout.LayoutParams(donor.arrow.relativeParams())
        }
        val value = TextView(this).apply {
            mimicTextOf(donor.value)
            layoutParams = RelativeLayout.LayoutParams(donor.value.relativeParams())
                .apply { addRule(RelativeLayout.START_OF, arrow.id) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = donor.row.background?.constantState?.newDrawable()
            minimumHeight = donor.row.minimumHeight
            layoutParams = LinearLayout.LayoutParams(donor.row.linearParams())
            addView(
                TextView(this@mimicRow).apply {
                    mimicTextOf(donor.label)
                    text = moduleString(R.string.hwhh_cache_entry)
                    layoutParams = LinearLayout.LayoutParams(donor.label.linearParams())
                },
            )
            addView(
                RelativeLayout(this@mimicRow).apply {
                    layoutParams = LinearLayout.LayoutParams(donor.tail.linearParams())
                    addView(value)
                    addView(arrow)
                },
            )
        }
        return row to value
    }

    private fun TextView.mimicTextOf(source: TextView) {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, source.textSize)
        setTextColor(source.textColors)
        typeface = source.typeface
        gravity = source.gravity
    }

    private fun View.linearParams() = layoutParams as LinearLayout.LayoutParams

    private fun View.relativeParams() = layoutParams as RelativeLayout.LayoutParams
}
