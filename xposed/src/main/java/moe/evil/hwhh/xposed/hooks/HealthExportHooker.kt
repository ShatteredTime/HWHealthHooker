package moe.evil.hwhh.xposed.hooks

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.highcapable.kavaref.extension.classOf
import com.huawei.ui.commonui.checkbox.HealthCheckBox
import com.huawei.ui.commonui.datepicker.HealthDatePickerDialog
import com.huawei.ui.commonui.popupview.PopViewList
import com.huawei.ui.commonui.titlebar.CustomTitleBar
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import moe.evil.hwhh.kdxref.HostBridge
import moe.evil.hwhh.kdxref.HostField
import moe.evil.hwhh.kdxref.describe
import moe.evil.hwhh.kdxref.firstConstructorOrNullLogged
import moe.evil.hwhh.kdxref.firstMethodOrNullLogged
import moe.evil.hwhh.kdxref.hostField
import moe.evil.hwhh.kdxref.hostMethod
import moe.evil.hwhh.kdxref.safeHook
import moe.evil.hwhh.kdxref.toClassOrLog
import moe.evil.hwhh.shared.HOOK_TARGET_PACKAGE
import moe.evil.hwhh.shared.HookRoot
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.xposed.R
import moe.evil.hwhh.xposed.model.HealthMetadata
import moe.evil.hwhh.xposed.model.HealthQueryRequest
import moe.evil.hwhh.xposed.sportdata.exporter.ensureExportDir
import moe.evil.hwhh.xposed.utils.DexKitBaseHooker
import moe.evil.hwhh.xposed.utils.ShareExporter
import moe.evil.hwhh.xposed.utils.ShareOutcome
import moe.evil.hwhh.xposed.utils.moduleString
import moe.evil.hwhh.xposed.utils.toast
import java.io.File
import java.lang.ref.WeakReference
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.WeakHashMap
import kotlin.concurrent.thread

@HookRoot(order = 5)
@OptIn(ExperimentalSerializationApi::class)
object HealthExportHooker : DexKitBaseHooker() {
    private const val HOME_FRAGMENT_CLASS = "com.huawei.ui.homehealth.HomeFragment"
    private const val ROW_ARROW_DRAWABLE = "ic_health_list_arrow_gray"
    private val log = HLog.of<HealthExportHooker>()
    private val json = Json
    private val commonUi by require { CommonUIHooker }
    private val query by require { HealthQueryHooker }
    private val metadata by require { HealthMetadataHooker }
    private val db by require { HiHealthDbHooker }
    private var titleBarField: HostField<CustomTitleBar>? = null
    private var currentTitleBar: WeakReference<CustomTitleBar>? = null
    private val exportPopupItems = WeakHashMap<PopViewList, ArrayList<String>>()

    private fun Activity.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // Host stub interfaces must be implemented by a named class under the kept
    // moe.evil.hwhh.xposed package: a SAM lambda becomes an R8 synthetic that keep
    // rules cannot reach, and since the interface is compileOnly R8 renames the
    // override, so the host's call lands on AbstractMethodError in release builds.
    @Suppress("ObjectLiteralToLambda")
    private fun dateSelected(onSelected: (Int, Int, Int) -> Unit) =
        object : HealthDatePickerDialog.DateSelectedListener {
            override fun onDateSelected(year: Int, month: Int, dayOfMonth: Int) =
                onSelected(year, month, dayOfMonth)
        }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

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
        val homeFragmentClazz = context(this@HealthExportHooker) {
            HealthExportHooker.HOME_FRAGMENT_CLASS.toClassOrLog()
        } ?: return

        titleBarField = hostField<CustomTitleBar>(
            label = "HomeFragment#titleBar",
            inPackage = HOME_FRAGMENT_CLASS.substringBeforeLast('.'),
        ) {
            declaredClass(homeFragmentClazz)
        } ?: return

        homeFragmentClazz.firstMethodOrNullLogged {
            name = "onActivityCreated"
            parameters(classOf<Bundle>())
        }?.safeHook {
            after {
                currentTitleBar = titleBarField?.on(instanceOrNull)?.let { WeakReference(it) }
            }
        }

        val setClickListener = hostMethod<Unit>(
            label = "PopViewList#setClickListener",
            inPackage = "com.huawei.ui.commonui.popupview",
        ) {
            declaredClass(classOf<PopViewList>())
            paramTypes(classOf<PopViewList.PopViewClickListener>())
        } ?: return

        classOf<PopViewList>().firstConstructorOrNullLogged {
            parameters(classOf<Context>(), classOf<View>(), classOf<ArrayList<String>>())
        }?.safeHook {
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
        val startCal = GregorianCalendar().apply { add(Calendar.DAY_OF_MONTH, -7) }
        val endCal = GregorianCalendar()
        val selected = LinkedHashSet<Int>()
        val textColor = HealthCheckBox(activity).currentTextColor
        val mutedColor = (textColor and 0x00FFFFFF) or (0x99 shl 24)

        fun rangeLabel(): String {
            fun d(c: GregorianCalendar) = "%04d-%02d-%02d".format(
                c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH),
            )
            return "${d(startCal)} ~ ${d(endCal)}"
        }

        val (timeRow, timeValue) = summaryRow(
            activity,
            textColor,
            mutedColor,
            activity.moduleString(R.string.hwhh_row_time),
        )
        timeValue.text = rangeLabel()
        timeRow.setOnClickListener {
            HealthDatePickerDialog(
                activity,
                dateSelected { y, m, day ->
                    startCal.set(y, m, day)
                    HealthDatePickerDialog(
                        activity,
                        dateSelected { y2, m2, day2 ->
                            endCal.set(y2, m2, day2)
                            timeValue.text = rangeLabel()
                        },
                        endCal,
                    ).show()
                },
                startCal,
            ).show()
        }

        val (idRow, idValue) = summaryRow(
            activity,
            textColor,
            mutedColor,
            activity.moduleString(R.string.hwhh_row_id),
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

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            val hPad = activity.dp(8)
            setPadding(hPad, 0, hPad, 0)
            addView(timeRow)
            addView(idRow)
        }

        commonUi.createCustomViewDialog(
            activity = activity,
            title = activity.moduleString(R.string.hwhh_export_title),
            contentView = content,
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
                    startCal.set(Calendar.HOUR_OF_DAY, 0)
                    startCal.set(Calendar.MINUTE, 0)
                    startCal.set(Calendar.SECOND, 0)
                    startCal.set(Calendar.MILLISECOND, 0)
                    endCal.set(Calendar.HOUR_OF_DAY, 23)
                    endCal.set(Calendar.MINUTE, 59)
                    endCal.set(Calendar.SECOND, 59)
                    endCal.set(Calendar.MILLISECOND, 999)

                    val progress = commonUi.createProgressDialog(
                        activity,
                        activity.moduleString(R.string.hwhh_exporting),
                    ).gracefulShow()
                    val exportTypes = selected.toList()
                    thread {
                        runCatching {
                            val dir = activity.applicationContext.ensureExportDir()
                            if (dir == null) {
                                commonUi.createNoTitleCustomAlertDialog(
                                    activity = activity,
                                    message = activity.moduleString(R.string.hwhh_export_dir_failed),
                                    positive = DialogButton(activity.moduleString(R.string.hwhh_ok)),
                                ).gracefulShow {
                                    toast(moduleString(R.string.hwhh_export_dir_failed))
                                }
                            } else {
                                val request = HealthQueryRequest(
                                    types = exportTypes,
                                    startTimeMs = startCal.timeInMillis,
                                    endTimeMs = endCal.timeInMillis,
                                    timeoutSec = 60L,
                                )
                                val response = query.queryData(activity, request).getOrThrow()
                                val out = File(
                                    dir,
                                    "health_export_${System.currentTimeMillis()}.json",
                                )
                                out.outputStream().use { json.encodeToStream(response, it) }
                                val shareOutcome = ShareExporter.share(
                                    activity = activity,
                                    target = out,
                                    chooserTitle = activity.moduleString(
                                        R.string.hwhh_share_title,
                                    ),
                                    mimeType = "application/json",
                                )
                                if (shareOutcome is ShareOutcome.Failed) {
                                    val cause = shareOutcome.error.describe()
                                    commonUi.createCustomTextAlertDialog(
                                        activity = activity,
                                        title = activity.moduleString(
                                            R.string.hwhh_share_failed_title,
                                        ),
                                        message = activity.moduleString(
                                            R.string.hwhh_error_detail,
                                            cause,
                                        ),
                                        positive = DialogButton(activity.moduleString(R.string.hwhh_ok)),
                                    ).gracefulShow {
                                        toast(moduleString(R.string.hwhh_share_failed, cause))
                                    }
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

    private fun summaryRow(
        activity: Activity,
        textColor: Int,
        mutedColor: Int,
        title: String,
    ): Pair<LinearLayout, TextView> {
        val value = TextView(activity).apply {
            setTextColor(mutedColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val ripple = TypedValue().also {
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setBackgroundResource(ripple.resourceId)
            layoutParams = matchWidth()
            val vPad = activity.dp(16)
            setPadding(0, vPad, 0, vPad)
            addView(
                TextView(activity).apply {
                    text = title
                    setTextColor(textColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    layoutParams =
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            addView(value)
            val arrowRes = activity.resources
                .getIdentifier(ROW_ARROW_DRAWABLE, "drawable", HOOK_TARGET_PACKAGE)
            if (arrowRes == 0) {
                log.debug { "Summary row drawable not found: $ROW_ARROW_DRAWABLE" }
            } else {
                addView(
                    ImageView(activity).apply {
                        setImageResource(arrowRes)
                        imageTintList = ColorStateList.valueOf(mutedColor)
                        val size = activity.dp(16)
                        layoutParams = LinearLayout.LayoutParams(size, size).apply {
                            marginStart = activity.dp(6)
                        }
                    },
                )
            }
        }
        return row to value
    }
}
