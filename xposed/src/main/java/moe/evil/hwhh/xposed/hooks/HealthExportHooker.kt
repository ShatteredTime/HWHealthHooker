package moe.evil.hwhh.xposed.hooks

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.ScrollView
import com.huawei.ui.commonui.checkbox.HealthCheckBox
import com.huawei.ui.commonui.popupview.PopViewList
import com.huawei.ui.commonui.titlebar.CustomTitleBar
import moe.evil.hwhh.shared.HookRoot
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.shared.log.describe
import moe.evil.hwhh.xposed.R
import moe.evil.hwhh.xposed.model.HealthMetadata
import moe.evil.hwhh.xposed.model.HealthQueryRequest
import moe.evil.hwhh.xposed.sportdata.exporter.ensureExportDir
import moe.evil.hwhh.xposed.utils.DateRangeRow
import moe.evil.hwhh.xposed.utils.DexKitBaseHooker
import moe.evil.hwhh.xposed.utils.ShareExporter
import moe.evil.hwhh.xposed.utils.ShareOutcome
import moe.evil.hwhh.xposed.utils.dialogContent
import moe.evil.hwhh.xposed.utils.dp
import moe.evil.hwhh.xposed.utils.hostTextColor
import moe.evil.hwhh.xposed.utils.matchWidth
import moe.evil.hwhh.xposed.utils.moduleString
import moe.evil.hwhh.xposed.utils.muted
import moe.evil.hwhh.xposed.utils.summaryRow
import moe.evil.hwhh.xposed.utils.toast
import moe.evil.hwhh.xposed.utils.wrapper.HostBridge
import moe.evil.hwhh.xposed.utils.wrapper.classOf
import moe.evil.hwhh.xposed.utils.wrapper.getOrNull
import moe.evil.hwhh.xposed.utils.wrapper.requireClass
import moe.evil.hwhh.xposed.utils.wrapper.requireConstructor
import moe.evil.hwhh.xposed.utils.wrapper.requireField
import moe.evil.hwhh.xposed.utils.wrapper.requireMethod
import moe.evil.hwhh.xposed.utils.wrapper.safeHook
import moe.evil.hwhh.xposed.utils.writeNdjson
import java.io.File
import java.lang.ref.WeakReference
import java.util.Calendar
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@HookRoot(order = 5)
object HealthExportHooker : DexKitBaseHooker() {
    private const val HOME_FRAGMENT_CLASS = "com.huawei.ui.homehealth.HomeFragment"
    private const val DEFAULT_DAYS_BACK = 7
    private const val SLICE_TIMEOUT_SEC = 30L
    private val log = HLog.of<HealthExportHooker>()
    private val commonUi by require { CommonUIHooker }
    private val query by require { HealthQueryHooker }
    private val metadata by require { HealthMetadataHooker }
    private val db by require { HiHealthDbHooker }
    private var currentTitleBar: WeakReference<CustomTitleBar>? = null
    private val exportPopupItems = WeakHashMap<PopViewList, ArrayList<String>>()

    // HealthCheckBox's button asset is a full 48dp touch target with the ~24dp visible
    // square centred in it, so ~12dp of transparent margin is baked into the drawable and
    // no amount of padding removes it. Pull the row back by that inset so the visible square
    // lines up with the dialog title. `indent = false` is for rows inside a scrolling list,
    // where the -12dp instead goes on the ScrollView itself (see idRow.setOnClickListener) —
    // putting it on every row would force the ScrollView to opt out of clipChildren too,
    // which also disables its scroll clip and makes scrolled content bleed past its bounds.
    private fun Activity.healthBox(indent: Boolean = true) = HealthCheckBox(this).apply {
        layoutParams = matchWidth().apply { if (indent) marginStart = -dp(12) }
        setPadding(0, paddingTop, paddingRight, paddingBottom)
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
                val titleBar = currentTitleBar?.get() ?: return@before
                if (args(1).cast<View?>() !== titleBar) return@before
                val activity = titleBar.context as? Activity ?: return@before
                val items = args(2).cast<ArrayList<String>?>() ?: return@before
                items.add(activity.moduleString(R.string.hwhh_export_title))
                exportPopupItems[instance<PopViewList>()] = items
            }
        }

        setClickListener.safeHook {
            before {
                val popup = instance<PopViewList>()
                val items = exportPopupItems.remove(popup) ?: return@before
                val original = args(0).cast<PopViewList.PopViewClickListener?>() ?: return@before
                val exportPosition = items.size - 1
                args(0).set(object : PopViewList.PopViewClickListener {
                    override fun setOnClick(position: Int) {
                        if (position != exportPosition) {
                            original.setOnClick(position)
                            return
                        }
                        (currentTitleBar?.get()?.context as? Activity)?.let(::showExportDialog)
                    }
                })
            }
        }
    }

    private fun showExportDialog(activity: Activity) {
        val loading = commonUi.createProgressDialog(
            activity,
            activity.moduleString(R.string.hwhh_loading_types),
        ).gracefulShow()
        thread {
            db.localTypes().fold(
                onFailure = { cause ->
                    log.error(cause) { "Local types unavailable" }
                    loading.dismiss()
                    activity.runOnUiThread {
                        commonUi.createCustomTextAlertDialog(
                            activity = activity,
                            title = activity.moduleString(R.string.hwhh_types_unavailable_title),
                            message = activity.moduleString(
                                R.string.hwhh_error_detail,
                                cause.describe(),
                            ),
                            positive = DialogButton(activity.moduleString(R.string.hwhh_ok)),
                        ).gracefulShow {
                            toast(moduleString(R.string.hwhh_types_unavailable))
                        }
                    }
                },
                onSuccess = { types ->
                    val resolved = metadata.resolveAll(types)
                        .onFailure { log.error(it) { "Metadata unavailable" } }
                    loading.dismiss()
                    activity.runOnUiThread {
                        when {
                            types.isEmpty() -> commonUi.createNoTitleCustomAlertDialog(
                                activity = activity,
                                message = activity.moduleString(R.string.hwhh_no_types),
                                positive = DialogButton(activity.moduleString(R.string.hwhh_ok)),
                            ).gracefulShow {
                                toast(moduleString(R.string.hwhh_no_types))
                            }

                            else -> resolved.fold(
                                onSuccess = { showTypePicker(activity, types, it) },
                                onFailure = { confirmDegraded(activity, types, it) },
                            )
                        }
                    }
                },
            )
        }
    }

    private fun confirmDegraded(activity: Activity, types: List<Int>, cause: Throwable) {
        commonUi.createCustomTextAlertDialog(
            activity = activity,
            title = activity.moduleString(R.string.hwhh_metadata_unavailable_title),
            message = activity.moduleString(
                R.string.hwhh_metadata_unavailable_message,
                cause.describe(),
            ),
            positive = DialogButton(activity.moduleString(R.string.hwhh_yes)) {
                showTypePicker(activity, types, emptyMap())
            },
            negative = DialogButton(activity.moduleString(R.string.hwhh_no)),
        ).gracefulShow {
            toast(moduleString(R.string.hwhh_metadata_degraded))
            showTypePicker(this, types, emptyMap())
        }
    }

    private fun showTypePicker(
        activity: Activity,
        types: List<Int>,
        names: Map<Int, HealthMetadata>,
    ) {
        val selected = LinkedHashSet<Int>()
        val textColor = activity.hostTextColor()
        val mutedColor = textColor.muted()

        val timeRange = DateRangeRow(activity, activity.moduleString(R.string.hwhh_row_time)) {
            add(Calendar.DAY_OF_MONTH, -DEFAULT_DAYS_BACK)
        }

        val (idRow, idValue) = activity.summaryRow(
            activity.moduleString(R.string.hwhh_row_id),
            textColor,
            mutedColor,
        )

        fun idSummary() =
            activity.moduleString(R.string.hwhh_id_summary, selected.size, types.size)
        idValue.text = idSummary()
        idRow.setOnClickListener {
            val boxes = types.associateWith { type ->
                activity.healthBox(indent = false).apply {
                    val name = names[type]?.name
                    text = if (name == null) {
                        type.toString()
                    } else {
                        SpannableString("$type  $name").apply {
                            setSpan(
                                ForegroundColorSpan(mutedColor),
                                0,
                                type.toString().length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                    }
                    isChecked = type in selected
                }
            }
            val typeList = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = matchWidth()
                boxes.values.forEach { addView(it) }
            }
            val scroll = ScrollView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    activity.dp(260),
                ).apply { marginStart = -activity.dp(12) }
                // idContent must opt out of clipChildren below (for this
                // view's and selectAll's negative marginStart), which also
                // stops idContent from clipping *this* view to its own
                // 260dp viewport, so scrolled-off rows would otherwise keep
                // painting past it. Self-clip regardless of the ancestor.
                outlineProvider = ViewOutlineProvider.BOUNDS
                clipToOutline = true
                addView(typeList)
            }
            val selectAll = activity.healthBox().apply {
                text = activity.moduleString(R.string.hwhh_select_all)
                isChecked = selected.size == types.size
                setOnCheckedChangeListener { _, checked ->
                    boxes.values.forEach { it.isChecked = checked }
                }
            }
            val idContent = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                layoutParams = matchWidth()
                setPadding(0, 0, 0, activity.dp(16))
                addView(selectAll)
                addView(scroll)
                post { (parent as? ViewGroup)?.clipChildren = false }
            }
            commonUi.createCustomViewDialog(
                activity = activity,
                title = activity.moduleString(R.string.hwhh_row_id),
                contentView = idContent,
                positive = DialogButton(activity.moduleString(R.string.hwhh_ok)) {
                    selected.clear()
                    selected += boxes.filterValues(HealthCheckBox::isChecked).keys
                    idValue.text = idSummary()
                },
                negative = DialogButton(activity.moduleString(R.string.hwhh_cancel)),
            ).gracefulShow()
        }

        commonUi.createCustomViewDialog(
            activity = activity,
            title = activity.moduleString(R.string.hwhh_export_title),
            contentView = activity.dialogContent(timeRange.view, idRow),
            positive = DialogButton(activity.moduleString(R.string.hwhh_export)) {
                if (selected.isEmpty()) {
                    commonUi.createNoTitleCustomAlertDialog(
                        activity = activity,
                        message = activity.moduleString(R.string.hwhh_no_type_selected),
                        positive = DialogButton(activity.moduleString(R.string.hwhh_ok)),
                    ).gracefulShow {
                        toast(moduleString(R.string.hwhh_no_type_selected))
                    }
                } else {
                    val cancelled = AtomicBoolean(false)
                    val progress = commonUi.createProgressDialog(
                        activity,
                        activity.moduleString(R.string.hwhh_exporting),
                    ) { cancelled.set(true) }.gracefulShow()
                    val exportTypes = selected.toList()
                    val exportNames = names.filterKeys(selected::contains)
                    thread {
                        runCatching {
                            val dir = activity.applicationContext.ensureExportDir() ?: run {
                                commonUi.createNoTitleCustomAlertDialog(
                                    activity = activity,
                                    message = activity.moduleString(R.string.hwhh_export_dir_failed),
                                    positive = DialogButton(activity.moduleString(R.string.hwhh_ok)),
                                ).gracefulShow {
                                    toast(moduleString(R.string.hwhh_export_dir_failed))
                                }
                                return@runCatching
                            }
                            val request = HealthQueryRequest(
                                types = exportTypes,
                                startTimeMs = timeRange.range.first,
                                endTimeMs = timeRange.range.last,
                                timeoutSec = SLICE_TIMEOUT_SEC,
                            )
                            val out = File(
                                dir,
                                "health_export_${System.currentTimeMillis()}.ndjson",
                            )
                            val summary = query.querySlices(activity, request).writeNdjson(
                                out = out,
                                request = request,
                                metadata = exportNames,
                                isCancelled = cancelled::get,
                            ) { done, total, rows ->
                                progress.setProgress(done * 100 / total)
                                progress.setMessage(
                                    activity.moduleString(
                                        R.string.hwhh_export_progress,
                                        done,
                                        total,
                                        rows,
                                    )
                                )
                            }
                            log.debug {
                                "Exported ${summary.count} rows over ${request.sliceCount} " +
                                        "slices, failed=${summary.failedSlices.size}, " +
                                        "cancelled=${summary.cancelled}"
                            }
                            if (summary.failedSlices.isNotEmpty()) {
                                activity.toast(
                                    activity.moduleString(
                                        R.string.hwhh_export_partial,
                                        summary.failedSlices.size,
                                        summary.count,
                                    )
                                )
                            }
                            if (summary.cancelled) {
                                activity.toast(
                                    activity.moduleString(R.string.hwhh_export_cancelled, out.name)
                                )
                                return@runCatching
                            }
                            val shareOutcome = ShareExporter.share(
                                activity = activity,
                                target = out,
                                chooserTitle = activity.moduleString(R.string.hwhh_share_title),
                                mimeType = "application/json",
                            )
                            if (shareOutcome is ShareOutcome.Failed) {
                                val cause = shareOutcome.error.describe()
                                commonUi.createCustomTextAlertDialog(
                                    activity = activity,
                                    title = activity.moduleString(R.string.hwhh_share_failed_title),
                                    message = activity.moduleString(
                                        R.string.hwhh_error_detail,
                                        cause,
                                    ),
                                    positive = DialogButton(activity.moduleString(R.string.hwhh_ok)),
                                ).gracefulShow {
                                    toast(moduleString(R.string.hwhh_share_failed, cause))
                                }
                            }
                        }.onFailure { e ->
                            log.error(e) { "Export failed" }
                            commonUi.createCustomTextAlertDialog(
                                activity = activity,
                                title = activity.moduleString(R.string.hwhh_export_failed_title),
                                message = activity.moduleString(
                                    R.string.hwhh_error_detail,
                                    e.describe(),
                                ),
                                positive = DialogButton(activity.moduleString(R.string.hwhh_ok)),
                            ).gracefulShow {
                                toast(moduleString(R.string.hwhh_export_failed, e.describe()))
                            }
                        }
                        progress.dismiss()
                    }
                }
            },
            negative = DialogButton(activity.moduleString(R.string.hwhh_cancel)),
        ).gracefulShow()
    }
}
